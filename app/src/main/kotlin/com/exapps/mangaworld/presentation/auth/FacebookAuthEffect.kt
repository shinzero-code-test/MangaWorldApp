package com.exapps.mangaworld.presentation.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * The single Facebook Login SDK registration point for the whole app.
 *
 * Both the post-onboarding login overlay (MainActivity) and the main
 * Navigation auth destinations need the LoginManager callback while they are
 * displayed. They are mutually exclusive screens, but each previously carried
 * its own copy of this remember + register + unregister block, routed through
 * one Activity field — exactly the shape that reintroduces "callback fired on
 * the wrong screen" bugs whenever one copy drifts. Keep ALL registration here
 * and call this from every Facebook entry point instead.
 *
 * Registration stays scoped to composition: entering disposes the previous
 * screen's callback via [onDispose], so at most one callback is ever live.
 */
@Composable
fun FacebookAuthEffect(
    onAccessToken: (String) -> Unit,
    onFailure: () -> Unit,
    setFacebookCallbackManager: (com.facebook.CallbackManager) -> Unit
) {
    val facebookCallbackManager = remember { com.facebook.CallbackManager.Factory.create() }
    DisposableEffect(facebookCallbackManager) {
        val loginManager = com.facebook.login.LoginManager.getInstance()
        val callback = object : com.facebook.FacebookCallback<com.facebook.login.LoginResult> {
            override fun onSuccess(result: com.facebook.login.LoginResult) {
                onAccessToken(result.accessToken.token)
            }
            override fun onCancel() = Unit
            override fun onError(error: com.facebook.FacebookException) {
                onFailure()
            }
        }
        setFacebookCallbackManager(facebookCallbackManager)
        loginManager.registerCallback(facebookCallbackManager, callback)
        onDispose { loginManager.unregisterCallback(facebookCallbackManager) }
    }
}
