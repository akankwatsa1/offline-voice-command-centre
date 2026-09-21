package com.voicecmd.center

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(VoiceCommandPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
