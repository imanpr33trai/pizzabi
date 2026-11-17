use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::task::{Context, Poll};

use axum::serve::Listener;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::{TcpListener, TcpStream};

use crate::error::ApiError;
use crate::middleware::is_valid_request_id;

const MAX_INSPECT_HEAD_BYTES: usize = 64 * 1024;
const HTTP2_PREFACE_PREFIX: &[u8] = b"PRI * HTTP/2.0\r\n";

pub struct HttpValidationListener {
    inner: TcpListener,
    is_production: bool,
}

impl HttpValidationListener {
    pub fn new(inner: TcpListener, is_production: bool) -> Self {
        Self {
            inner,
            is_production,
        }
    }
}

impl Listener for HttpValidationListener {
    type Io = ValidatedHttpStream;
    type Addr = SocketAddr;

    async fn accept(&mut self) -> (Self::Io, Self::Addr) {
        let (stream, addr) = <TcpListener as Listener>::accept(&mut self.inner).await;
        (ValidatedHttpStream::new(stream, self.is_production), addr)
    }

    fn local_addr(&self) -> io::Result<Self::Addr> {
        self.inner.local_addr()
    }
}

enum StreamState {
    InspectingHeaders(Vec<u8>),
    Passthrough(Vec<u8>, usize),
    DirectPassthrough,
    WritingError {
        resp: Vec<u8>,
        pos: usize,
        flushed: bool,
    },
    Done,
}

pub struct ValidatedHttpStream {
    inner: TcpStream,
    is_production: bool,
    state: StreamState,
}

impl ValidatedHttpStream {
    pub fn new(inner: TcpStream, is_production: bool) -> Self {
        Self {
            inner,
            is_production,
            state: StreamState::InspectingHeaders(Vec::with_capacity(1024)),
        }
    }

    fn is_closed_after_rejection(&self) -> bool {
        matches!(
            self.state,
            StreamState::WritingError { .. } | StreamState::Done
        )
    }
}

impl AsyncRead for ValidatedHttpStream {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        out: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.as_mut().get_mut();
        if out.remaining() == 0 {
            return Poll::Ready(Ok(()));
        }

        loop {
            match &mut this.state {
                StreamState::DirectPassthrough => {
                    return Pin::new(&mut this.inner).poll_read(cx, out);
                }
                StreamState::Passthrough(prefix, pos) => {
                    let available = prefix.len().saturating_sub(*pos);
                    if available == 0 {
                        this.state = StreamState::DirectPassthrough;
                        continue;
                    }
                    let to_copy = available.min(out.remaining());
                    out.put_slice(&prefix[*pos..*pos + to_copy]);
                    *pos += to_copy;
                    if *pos >= prefix.len() {
                        this.state = StreamState::DirectPassthrough;
                    }
                    return Poll::Ready(Ok(()));
                }
                StreamState::InspectingHeaders(buf) => {
                    let mut tmp = [0u8; 4096];
                    let mut read_buf = ReadBuf::new(&mut tmp);
                    match Pin::new(&mut this.inner).poll_read(cx, &mut read_buf) {
                        Poll::Pending => return Poll::Pending,
                        Poll::Ready(Err(err)) => return Poll::Ready(Err(err)),
                        Poll::Ready(Ok(())) => {
                            let filled = read_buf.filled();
                            if filled.is_empty() {
                                if buf.is_empty() {
                                    this.state = StreamState::Done;
                                    return Poll::Ready(Ok(()));
                                }
                                let prefix = std::mem::take(buf);
                                this.state = StreamState::Passthrough(prefix, 0);
                                continue;
                            }
                            buf.extend_from_slice(filled);
                            if buf.starts_with(HTTP2_PREFACE_PREFIX)
                                || buf.len() >= MAX_INSPECT_HEAD_BYTES
                            {
                                let prefix = std::mem::take(buf);
                                this.state = StreamState::Passthrough(prefix, 0);
                                continue;
                            }
                            if let Some(header_end) = find_header_end(buf) {
                                let head = &buf[..header_end];
                                let resp = reject_invalid_content_length(head, this.is_production);
                                if let Some(resp) = resp {
                                    this.state = StreamState::WritingError {
                                        resp,
                                        pos: 0,
                                        flushed: false,
                                    };
                                    continue;
                                }
                                let prefix = std::mem::take(buf);
                                this.state = StreamState::Passthrough(prefix, 0);
                                continue;
                            }
                        }
                    }
                }
                StreamState::WritingError { resp, pos, flushed } => {
                    while *pos < resp.len() {
                        match Pin::new(&mut this.inner).poll_write(cx, &resp[*pos..]) {
                            Poll::Pending => return Poll::Pending,
                            Poll::Ready(Ok(0)) | Poll::Ready(Err(_)) => {
                                this.state = StreamState::Done;
                                return Poll::Ready(Ok(()));
                            }
                            Poll::Ready(Ok(n)) => {
                                *pos += n;
                            }
                        }
                    }
                    if !*flushed {
                        match Pin::new(&mut this.inner).poll_flush(cx) {
                            Poll::Pending => return Poll::Pending,
                            Poll::Ready(Ok(())) => *flushed = true,
                            Poll::Ready(Err(_)) => {
                                this.state = StreamState::Done;
                                return Poll::Ready(Ok(()));
                            }
                        }
                    }
                    match Pin::new(&mut this.inner).poll_shutdown(cx) {
                        Poll::Pending => return Poll::Pending,
                        Poll::Ready(_) => {
                            this.state = StreamState::Done;
                            return Poll::Ready(Ok(()));
                        }
                    }
                }
                StreamState::Done => {
                    return Poll::Ready(Ok(()));
                }
            }
        }
    }
}

impl AsyncWrite for ValidatedHttpStream {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        if self.is_closed_after_rejection() {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "connection closed after invalid request",
            )));
        }
        Pin::new(&mut self.inner).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if self.is_closed_after_rejection() {
            return Poll::Ready(Ok(()));
        }
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if self.is_closed_after_rejection() {
            return Poll::Ready(Ok(()));
        }
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

fn find_header_end(buf: &[u8]) -> Option<usize> {
    let pos = buf.windows(4).position(|w| w == b"\r\n\r\n")?;
    Some(pos + 4)
}

fn has_invalid_content_length(header_bytes: &[u8]) -> bool {
    let mut lines = header_bytes.split(|&b| b == b'\n');
    let _request_line = lines.next();
    let mut seen_len: Option<u64> = None;

    for raw_line in lines {
        let line = raw_line.strip_suffix(b"\r").unwrap_or(raw_line);
        if line.is_empty() {
            continue;
        }
        let Some(colon_pos) = line.iter().position(|&b| b == b':') else {
            continue;
        };
        let name = &line[..colon_pos];
        if !name.eq_ignore_ascii_case(b"content-length") {
            continue;
        }
        let Ok(val_str) = std::str::from_utf8(&line[colon_pos + 1..]) else {
            return true;
        };
        let trimmed = val_str.trim_matches(|c| c == ' ' || c == '\t');
        if trimmed.is_empty() || !trimmed.bytes().all(|b| b.is_ascii_digit()) {
            return true;
        }
        let Ok(parsed) = trimmed.parse::<u64>() else {
            return true;
        };
        if let Some(prev) = seen_len {
            if prev != parsed {
                return true;
            }
        } else {
            seen_len = Some(parsed);
        }
    }

    false
}

fn extract_valid_request_id(header_bytes: &[u8]) -> Option<String> {
    let mut lines = header_bytes.split(|&b| b == b'\n');
    let _request_line = lines.next();

    for raw_line in lines {
        let line = raw_line.strip_suffix(b"\r").unwrap_or(raw_line);
        let Some(colon_pos) = line.iter().position(|&b| b == b':') else {
            continue;
        };
        let name = &line[..colon_pos];
        if !name.eq_ignore_ascii_case(b"x-request-id") {
            continue;
        }
        if let Ok(val_str) = std::str::from_utf8(&line[colon_pos + 1..]) {
            let trimmed = val_str.trim();
            if is_valid_request_id(trimmed) {
                return Some(trimmed.to_owned());
            }
        }
    }

    None
}

fn reject_invalid_content_length(header_bytes: &[u8], is_production: bool) -> Option<Vec<u8>> {
    if !has_invalid_content_length(header_bytes) {
        return None;
    }

    let cid = extract_valid_request_id(header_bytes)
        .unwrap_or_else(|| pizzora_db::new_uuid_v4().to_string());
    let msg = "Content-Length header is invalid.";
    let envelope = ApiError::validation("INVALID_CONTENT_LENGTH", msg).to_envelope(&cid);
    let body = serde_json::to_vec(&envelope).unwrap_or_else(|_| {
        format!(
            "{{\"error\":{{\"code\":\"INVALID_CONTENT_LENGTH\",\"message\":\"{msg}\",\"details\":{{}}}},\"correlation_id\":\"{cid}\",\"timestamp\":\"\"}}"
        )
        .into_bytes()
    });

    let mut headers = format!(
        "HTTP/1.1 400 Bad Request\r\n\
         content-type: application/json\r\n\
         content-length: {}\r\n\
         x-request-id: {cid}\r\n\
         cache-control: no-store\r\n\
         pragma: no-cache\r\n\
         x-content-type-options: nosniff\r\n\
         x-frame-options: DENY\r\n\
         referrer-policy: no-referrer\r\n\
         content-security-policy: default-src 'none'; frame-ancestors 'none'\r\n\
         permissions-policy: geolocation=(), microphone=(), camera=()\r\n\
         cross-origin-resource-policy: same-origin\r\n",
        body.len()
    );
    if is_production {
        headers.push_str("strict-transport-security: max-age=31536000; includeSubDomains\r\n");
    }
    headers.push_str("connection: close\r\n\r\n");

    let mut out = headers.into_bytes();
    out.extend_from_slice(&body);
    Some(out)
}
