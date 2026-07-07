package com.exapps.mangaworld.core.data

/**
 * Holds the Facebook CallbackManager instance so it can be accessed
 * from Activity.onActivityResult to forward Facebook login results.
 */
object FacebookCallbackHolder {
    var callbackManager: com.facebook.CallbackManager? = null
}
