package com.cyberdrill.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.cyberdrill.app.ui.CyberDrillNav
import com.cyberdrill.app.ui.CyberDrillTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CyberDrillTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CyberDrillNav()
                }
            }
        }
    }
}
