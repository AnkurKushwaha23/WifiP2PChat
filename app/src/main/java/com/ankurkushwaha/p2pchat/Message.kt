package com.ankurkushwaha.p2pchat

data class Message(
    val content: String,
    val isSent: Boolean // `true` for sent messages, `false` for received
)

