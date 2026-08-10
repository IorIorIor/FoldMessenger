package com.foldmessenger.app

import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The animated aura-heart background (UfoldedFX), replacing the baked PNGs.
 *
 * The shader and its four states are baked into assets/heart-view.html, which
 * renders fullscreen in a WebView and exposes window.HeartFX. This wrapper names
 * the states and makes them safe to request at any time: calls made before the
 * page has loaded are held and replayed once it is ready, and asking for the
 * state already on screen is ignored so a re-render (fold, resume) doesn't
 * restart the transition.
 */
class FxBackground(private val webView: WebView) {

    private var ready = false
    private var pending: String? = null
    private var current: String? = null

    init {
        webView.settings.javaScriptEnabled = true
        // matches the page's own CSS background, so there is no white flash
        // between the WebView attaching and the first rendered frame
        webView.setBackgroundColor(BASE_COLOR)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        // purely decorative: never let it take a touch from the viewer
        webView.setOnTouchListener { _, _ -> true }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                ready = true
                pending?.let { view.evaluateJavascript(setStateJs(it), null) }
                pending = null
            }
        }
        webView.loadUrl("file:///android_asset/heart-view.html")
    }

    /** Animate to one of the four baked states. */
    fun show(state: String) {
        if (state == current) return
        current = state
        if (ready) webView.evaluateJavascript(setStateJs(state), null) else pending = state
    }

    /** Freeze the animation while the viewer is off screen. */
    fun pause() {
        if (ready) webView.evaluateJavascript("HeartFX.pause()", null)
        webView.onPause()
    }

    fun resume() {
        webView.onResume()
        if (ready) webView.evaluateJavascript("HeartFX.resume()", null)
    }

    private fun setStateJs(state: String) = "HeartFX.setState('$state')"

    companion object {
        const val IDLE = "IDLE"
        const val TEXT_MESSAGE = "TEXT MESSAGE"
        const val MEDIA_MESSAGE = "MEDIA MESSAGE"
        const val NEW_REVEAL = "NEW REVEAL"

        private val BASE_COLOR = Color.parseColor("#14081f")
    }
}
