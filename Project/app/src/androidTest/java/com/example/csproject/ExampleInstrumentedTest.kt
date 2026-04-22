package com.example.csproject

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

// test that runs on a real device
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // check if package name is right
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.csproject", appContext.packageName)
    }
}
