package com.osamu.aide

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.ai.core.GoogleAuthManager
import com.osamu.aide.core.ui.theme.AideTheme
import com.osamu.aide.navigation.AideNavHost
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val keys: ApiKeyStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleOAuthRedirect(intent)
        enableEdgeToEdge()
        setContent {
            AideTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AideNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthRedirect(intent)
    }

    private fun handleOAuthRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        // Scheme only: Google's redirect is `scheme:/oauth2redirect`, which
        // has no authority, so `uri.host` is null and matching on it drops
        // every callback silently.
        if (uri.scheme == GoogleAuthManager.DEFAULT_REDIRECT_URI.substringBefore(':')) {
            lifecycleScope.launch {
                val authManager = GoogleAuthManager(keys)
                val result = authManager.handleRedirectUri(uri)
                result.fold(
                    onSuccess = { profile ->
                        Toast.makeText(
                            this@MainActivity,
                            "Signed in to Gemini as ${profile.email ?: "Google Account"}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            this@MainActivity,
                            "Sign-in failed: ${err.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }
}
