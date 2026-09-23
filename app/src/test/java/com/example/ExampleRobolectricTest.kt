package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.wagateway.QrCodeUtil
import com.example.wagateway.WaMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.16.x does not ship an android-all jar for API 36, so the sandbox
// SDK is pinned to a supported level (see src/test/resources/robolectric.properties).
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("WA Gateway", appName)
  }

  @Test
  fun `qr code bitmap generation generates non-null bitmap`() {
    val qr = QrCodeUtil.generateQrBitmap("2@sample_qr_pairing_data", 256)
    assertNotNull(qr)
  }

  @Test
  fun `wa message model stores incoming and outgoing properties`() {
    val msg = WaMessage(sender = "6281234567890", text = "Hello Gateway", timestamp = 1700000000)
    assertEquals("6281234567890", msg.sender)
    assertEquals("Hello Gateway", msg.text)
    assertEquals(false, msg.isOutgoing)
  }

  @Test
  fun `pairing mode defaults to QR and can toggle to CODE`() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm = com.example.wagateway.WaGatewayViewModel(app)
    assertEquals(com.example.wagateway.PairingMode.QR, vm.pairingMode.value)

    vm.setPairingMode(com.example.wagateway.PairingMode.CODE)
    assertEquals(com.example.wagateway.PairingMode.CODE, vm.pairingMode.value)

    vm.pairingPhone.value = "628123456789"
    assertEquals("628123456789", vm.pairingPhone.value)
  }
}

