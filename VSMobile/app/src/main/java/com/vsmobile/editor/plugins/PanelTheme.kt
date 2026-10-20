package com.vsmobile.editor.plugins

import org.json.JSONObject

data class PanelTokens(
    val background: String,
    val surface: String,
    val divider: String,
    val accent: String,
    val text: String,
    val muted: String,
    val danger: String
)

object PanelTheme {

    fun css(tokens: PanelTokens): String = """
:root{--vsm-bg:${tokens.background};--vsm-surface:${tokens.surface};--vsm-divider:${tokens.divider};--vsm-accent:${tokens.accent};--vsm-text:${tokens.text};--vsm-muted:${tokens.muted};--vsm-danger:${tokens.danger};--vsm-font:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;--vsm-mono:ui-monospace,"Roboto Mono",Menlo,Consolas,monospace;--vsm-radius:8px}
:where(html,body){margin:0;background:var(--vsm-bg);color:var(--vsm-text);font:15px/1.5 var(--vsm-font)}
:where(body){padding:16px}
:where(a){color:var(--vsm-accent)}
:where(button){font:inherit;color:#111111;background:var(--vsm-accent);border:0;border-radius:var(--vsm-radius);padding:8px 14px}
:where(button:disabled){opacity:.5}
:where(input,textarea,select){font:inherit;color:var(--vsm-text);background:var(--vsm-surface);border:1px solid var(--vsm-divider);border-radius:var(--vsm-radius);padding:8px 10px;box-sizing:border-box}
:where(input,textarea,select):focus{outline:2px solid var(--vsm-accent);outline-offset:0}
:where(pre,code){font-family:var(--vsm-mono);font-size:13px}
:where(pre){background:var(--vsm-surface);border-radius:var(--vsm-radius);padding:10px;overflow:auto}
:where(hr){border:0;border-top:1px solid var(--vsm-divider)}
""".trimIndent()

    fun csp(network: Boolean): String =
        if (network) {
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "img-src data: blob: https:; font-src data:; connect-src https:"
        } else {
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "img-src data: blob:; font-src data:; connect-src 'none'"
        }

    fun document(panel: PluginPanel, tokens: PanelTokens, network: Boolean, shim: String): String = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="${csp(network)}">
<style>${css(tokens)}</style>
<script>window.__bridge={call:function(m,a){return VsmHost.call(m,a);}};window.__panelId=${JSONObject.quote(panel.id)};</script>
<script>$shim</script>
</head>
<body>
${panel.html}
</body>
</html>
""".trimIndent()
}
