package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class PhoneCommandsTest {

    private val evening = LocalTime.of(20, 0)

    private fun parse(text: String, fromUser: Boolean = true) = PhoneCommands.parse(text, fromUser, evening)

    @Test fun typedCallsAreDirect() {
        assertEquals(Step.Call("Ali", true), parse("call Ali"))
        assertEquals(Step.Call("Ali Khan", true), parse("please call Ali Khan"))
        assertEquals(Step.Call("Ali", true), parse("Ali ko call karo"))
        assertEquals(Step.Call("ammi", true), parse("ammi ko phone lagao"))
    }

    @Test fun modelCallsAndDialOnlyOpenTheDialer() {
        assertEquals(Step.Call("Ali", false), parse("call Ali", fromUser = false))
        assertEquals(Step.Call("03001234567", false), parse("dial 03001234567"))
    }

    @Test fun callMeIsNotACall() {
        assertEquals(null, parse("call me Rex"))
    }

    @Test fun messages() {
        assertEquals(Step.WhatsApp("Ali", "running late"), parse("whatsapp Ali: running late"))
        assertEquals(Step.WhatsApp("Ahmed", "I'm home"), parse("send a whatsapp to Ahmed saying I'm home"))
        assertEquals(Step.WhatsApp("Sara", null), parse("whatsapp Sara"))
        assertEquals(Step.Sms("mom", "on my way"), parse("text mom: on my way"))
        assertEquals(Step.Sms("Bilal", "hi"), parse("send an sms to Bilal saying hi"))
        assertEquals(null, parse("text me a joke"))
    }

    @Test fun flashlight() {
        assertEquals(Step.Flashlight(true), parse("flashlight on"))
        assertEquals(Step.Flashlight(false), parse("turn off the torch"))
        assertEquals(Step.Flashlight(true), parse("turn the flashlight on"))
        assertEquals(Step.Flashlight(true), parse("torch"))
        assertEquals(Step.Flashlight(true), parse("torch jalao"))
        assertEquals(Step.Flashlight(false), parse("torch band karo"))
    }

    @Test fun bluetoothAndWifi() {
        assertEquals(Step.Bluetooth(true), parse("turn on bluetooth"))
        assertEquals(Step.Bluetooth(false), parse("bluetooth off"))
        assertEquals(Step.Wifi(false), parse("switch wifi off"))
        assertEquals(Step.Wifi(true), parse("turn on wi-fi"))
    }

    @Test fun alarms() {
        assertEquals("at 8 pm, \"7\" is 7 am", Step.Alarm(7, 0, null), parse("alarm 7"))
        assertEquals("at 8 pm, \"9\" is 9 pm", Step.Alarm(21, 0, null), parse("alarm 9"))
        assertEquals(Step.Alarm(6, 30, null), parse("set an alarm for 6:30 am"))
        assertEquals(Step.Alarm(19, 30, null), parse("alarm 7:30 pm"))
        assertEquals(Step.Alarm(19, 15, null), parse("set alarm for 19:15"))
        assertEquals(Step.Alarm(6, 0, "gym"), parse("alarm 6 am for gym"))
        assertEquals(Step.Alarm(5, 45, null), parse("wake me up at 5.45 tomorrow"))
        assertEquals(Step.Alarm(20, 0, null), parse("alarm 8 tonight"))
        assertEquals(Step.Alarm(0, 0, null), parse("alarm 12 am"))
        assertEquals(Step.Alarm(12, 0, null), parse("alarm 12 pm"))
        assertEquals(null, parse("alarm tomorrow"))
        assertEquals(null, parse("alarm 7:75"))
    }

    @Test fun timers() {
        assertEquals(Step.Timer(600), parse("timer 10 minutes"))
        assertEquals(Step.Timer(5400), parse("set a timer for 1 hour 30 min"))
        assertEquals(Step.Timer(90), parse("timer 90 sec"))
        assertEquals(Step.Timer(300), parse("timer 5"))
        assertEquals(Step.Timer(1800), parse("timer half an hour"))
    }

    @Test fun otherCommandsAreLeftAlone() {
        assertEquals(null, parse("open whatsapp"))
        assertEquals(null, parse("what time is it"))
        assertEquals(null, parse("send to benco: hello"))
        assertEquals(null, parse("should I call Ali later?"))
    }
}
