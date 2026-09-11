package app.auriel.cobalt.content

import org.chromium.components.embedder_support.delegate.WebContentsDelegateAndroid
import org.chromium.content_public.common.ResourceRequestBody
import org.chromium.url.GURL

/**
 * The `WebContentsDelegate` every Cobalt tab has.
 *
 * Chrome gives each tab one from native code inside `TabAndroid`, which Cobalt
 * does not use, so until 0.4.1 Cobalt's tabs had none, and Chromium reads a
 * missing delegate as "no": every download was refused before it started
 * (`DownloadRequestLimiter::CanDownload`). Attaching one is
 * `CobaltWebContentsDelegate.attach`, which Cobalt adds to Chromium's build
 * (tools/patches/cobalt-webcontents-delegate.py).
 *
 * The native half is Chromium's stock `WebContentsDelegateAndroid`, whose
 * defaults are the right ones for what Cobalt does not override: downloads are
 * allowed, and anything this class does not answer gets content's default.
 */
internal class CobaltDelegate(
    private val onNewTab: (String) -> Unit,
) : WebContentsDelegateAndroid() {

    /** `target=_blank` and friends: a Cobalt tab, in Cobalt's model. */
    override fun openNewTab(
        url: GURL,
        extraHeaders: String?,
        postData: ResourceRequestBody?,
        disposition: Int,
        isRendererInitiated: Boolean,
    ) {
        // POST bodies and extra headers are dropped: a new tab is opened by
        // URL. The rare form that posts into a new window lands on its GET
        // form, which is what content does without a delegate anyway.
        if (url.isValid) onNewTab(url.spec)
    }
}
