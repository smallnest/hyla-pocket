package io.rpcx.hylapocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.rpcx.hylapocket.ui.HylaPocketApp
import io.rpcx.hylapocket.ui.HylaViewModel

class MainActivity : ComponentActivity() {
    private val model: HylaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HylaPocketApp(model) }
    }
}
