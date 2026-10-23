package com.mmcdole.tether.data.world

import javax.crypto.KeyGenerator

/** The real cipher code with a plain JVM AES key in place of the Android Keystore. */
fun testSecrets() = LoginSecrets { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
