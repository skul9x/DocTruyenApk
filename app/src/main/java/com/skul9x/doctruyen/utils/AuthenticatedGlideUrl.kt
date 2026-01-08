package com.skul9x.doctruyen.utils

import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.Headers

/**
 * Custom GlideUrl that uses ONLY the URL for caching key.
 * Standard GlideUrl includes headers in the cache key, so if cookies/UA change,
 * the cache is invalidated. We want to persist cache even if headers change.
 */
class AuthenticatedGlideUrl(url: String, headers: Headers) : GlideUrl(url, headers) {

    override fun getCacheKey(): String {
        // Only return the URL string, ignoring headers
        return toStringUrl()
    }
}
