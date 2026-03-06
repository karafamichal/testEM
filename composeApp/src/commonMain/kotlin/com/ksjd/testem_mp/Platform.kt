package com.ksjd.testem_mp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform