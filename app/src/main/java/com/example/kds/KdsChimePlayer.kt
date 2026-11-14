package com.example.kds

import android.media.AudioManager
import android.media.ToneGenerator

interface KdsChimePlayer {
  fun playNewTicketChime()
}

class ToneGeneratorKdsChimePlayer : KdsChimePlayer {
  override fun playNewTicketChime() {
    runCatching {
      val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
      try {
        generator.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
      } finally {
        generator.release()
      }
    }
  }
}
