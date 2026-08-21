package com.osamu.aide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.osamu.aide.core.ui.theme.AideTheme
import com.osamu.aide.navigation.AideNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AideTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AideNavHost()
                }
            }
        }
    }
}
