package com.tennisbird.app

import com.google.firebase.database.FirebaseDatabase

object FirebaseWebUrlChecker {
    private const val URL_NODE = "url"

    fun checkUrl(onUrlFound: (String) -> Unit) {
        try {
            FirebaseDatabase
                .getInstance()
                .reference
                .child(URL_NODE)
                .get()
                .addOnSuccessListener { snapshot ->
                    val url = snapshot.getValue(String::class.java)?.trim().orEmpty()
                    if (WebUrlStore.isWebUrl(url)) {
                        onUrlFound(url)
                    }
                }
                .addOnFailureListener {
                    // No Firebase config yet, no network, or empty project: stay on the native game.
                }
        } catch (_: Throwable) {
            // Firebase is optional until google-services.json is added.
        }
    }
}
