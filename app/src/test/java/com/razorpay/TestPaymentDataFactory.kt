package com.razorpay

object TestPaymentDataFactory {
  fun create(
    orderId: String? = null,
    paymentId: String? = null,
    signature: String? = null,
    userEmail: String? = null,
    userContact: String? = null,
    externalWallet: String? = null,
  ): PaymentData = PaymentData().apply {
    if (orderId != null) setOrderId(orderId)
    if (paymentId != null) setPaymentId(paymentId)
    if (signature != null) setSignature(signature)
    if (userEmail != null) setUserEmail(userEmail)
    if (userContact != null) setUserContact(userContact)
    if (externalWallet != null) setExternalWallet(externalWallet)
  }
}
