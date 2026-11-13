package app.hylapocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.hylapocket.ui.HylaPocketApp
import app.hylapocket.ui.HylaViewModel

class MainActivity : ComponentActivity() {
    private val model: HylaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HylaPocketApp(model) }
    }
}
