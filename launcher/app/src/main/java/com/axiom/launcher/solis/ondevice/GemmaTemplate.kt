package com.axiom.launcher.solis.ondevice

/**
 * Gemma 4's chat format, fixed for apps that run tools themselves. The template inside the model file closes
 * the model's turn right after a tool call, so the tool's answer lands outside the turn and Gemma stops
 * without replying. The copy in assets keeps the call, its response and the reply in one turn, as the model
 * writes them (checked against the real model; see the on-device tests).
 */
object GemmaTemplate {
    const val ID = "gemma4"
    const val ASSET = "ondevice/gemma4_chat_template.jinja"

    /**
     * Added to the system prompt for on-device models: after a tool that fully answers the request (a timer
     * that is now set), small models tend to end their turn without a word.
     */
    const val TOOL_REPLY_RULE = "After a tool returns, always reply to the user in one short sentence saying what happened."
}
