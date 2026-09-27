"""Minimal HTML pages of the issuer portal: create an OpenID4VCI credential offer and show its QR."""
from __future__ import annotations

import html

import segno

from .attestations import CREDENTIAL_TYPES

_CSS = """
:root{--blue:#002F87;--navy:#192C70;--sky:#43B2ED;--red:#EA0029;--bg:#F4F6FB;--card:#fff;--text:#192C70;
--muted:#5B6690;--line:#DDE3F0}
@media (prefers-color-scheme: dark){:root:not([data-theme=light]){--bg:#0D1633;--card:#16214A;--text:#EAF0FF;
--muted:#A9B4D6;--line:#2A3769}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,
"Segoe UI",Roboto,sans-serif}header{background:var(--blue);color:#fff;padding:14px 16px;display:flex;
align-items:center;gap:12px}header img{height:34px;background:#fff;border-radius:8px;padding:3px 6px}
header b{font-size:18px}main{max-width:880px;margin:0 auto;padding:20px 16px 48px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:20px;margin:16px 0}
h1{font-size:24px;margin:8px 0}h2{font-size:18px;margin:0 0 12px}.muted{color:var(--muted);font-size:14px}
label{display:block;font-weight:600;margin:12px 0 4px}input[type=text],input[type=date],select,input[type=file]{
width:100%;padding:10px;border:1px solid var(--line);border-radius:10px;background:var(--bg);color:var(--text);
font:inherit}.row{display:grid;grid-template-columns:1fr 1fr;gap:12px}@media(max-width:600px){.row{
grid-template-columns:1fr}}.types label{font-weight:500;display:flex;gap:10px;align-items:flex-start;
border:1px solid var(--line);border-radius:12px;padding:10px 12px;margin:8px 0;cursor:pointer}
.types small{display:block;color:var(--muted)}button{background:var(--red);color:#fff;border:0;
border-radius:24px;padding:12px 26px;font:600 16px system-ui;cursor:pointer;margin-top:16px}
.tabs{display:flex;gap:8px;margin-bottom:8px}.tabs a{padding:8px 14px;border-radius:20px;text-decoration:none;
color:var(--blue);border:1px solid var(--line);background:var(--card)}.tabs a.on{background:var(--blue);color:#fff}
.qr{display:flex;gap:24px;flex-wrap:wrap;align-items:center}.qr svg{background:#fff;border-radius:12px;
padding:8px;max-width:100%;height:auto}.code{font:700 34px ui-monospace,monospace;letter-spacing:6px;
color:var(--red)}.pill{display:inline-block;border-radius:12px;padding:2px 10px;font-size:13px;
background:var(--line)}.ok{color:#0a8a4a}.bad{color:var(--red)}code{word-break:break-all;font-size:12px}
ul.report{padding-left:18px;font-size:14px}
"""


def _page(title: str, body: str, refresh: bool = False) -> str:
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{html.escape(title)}</title>
<link rel="icon" href="/static/emblem.svg"><style>{_CSS}</style></head><body>
<header><img src="/static/logo.svg" alt="IN Groupe"><b>getYourID Issuer</b><span class="pill">TEST</span></header>
<main>{body}</main></body></html>"""


def _type_boxes(default: tuple[str, ...]) -> str:
    out = []
    for t in CREDENTIAL_TYPES.values():
        chk = " checked" if t.config_id in default else ""
        out.append(f'<label><input type="checkbox" name="credentials" value="{t.config_id}"{chk}>'
                   f'<span>{html.escape(t.name)}<small>{html.escape(t.description)}</small></span></label>')
    return "".join(out)


def index_page(allow_manual: bool, mode: str = "document") -> str:
    tabs = ('<div class="tabs"><a href="/issuer?mode=document" class="{d}">Verify a document</a>'
            '{m}</div>').format(d="on" if mode != "manual" else "",
                                m=f'<a href="/issuer?mode=manual" class="{"on" if mode == "manual" else ""}">'
                                  'Enter test data</a>' if allow_manual else "")
    common = f"""<h2>Credentials to offer</h2><div class="types">{_type_boxes(tuple(CREDENTIAL_TYPES))}</div>
<label style="font-weight:500"><input type="checkbox" name="tx_code" value="1" checked> Protect the offer with a
6-digit transaction code (the wallet asks for it)</label><button type="submit">Create offer QR code</button>"""
    if mode == "manual" and allow_manual:
        form = f"""<form class="card" method="post" action="/issuer/offer" enctype="multipart/form-data">
<input type="hidden" name="mode" value="manual"><h2>Holder data (self-asserted test data)</h2>
<p class="muted">Nothing is verified: credentials issued from typed data are marked
<code>manual_entry_unverified</code> in their evidence namespace.</p>
<div class="row"><div><label>Family name</label><input type="text" name="family_name" required></div>
<div><label>Given name(s)</label><input type="text" name="given_name" required></div></div>
<div class="row"><div><label>Date of birth</label><input type="date" name="birth_date" required></div>
<div><label>Sex</label><select name="sex"><option value="F">Female</option><option value="M">Male</option>
<option value="X">Unspecified</option></select></div></div>
<div class="row"><div><label>Nationality (ISO alpha-3, e.g. FRA)</label><input type="text" name="nationality"
maxlength="3" required></div><div><label>Document number</label><input type="text" name="document_number" required>
</div></div><div class="row"><div><label>Document expiry</label><input type="date" name="document_expiry"></div>
<div><label>Portrait photo (JPEG/PNG, one face)</label><input type="file" name="portrait" accept="image/*"></div>
</div>{common}</form>"""
    else:
        form = f"""<form class="card" method="post" action="/issuer/offer" enctype="multipart/form-data">
<input type="hidden" name="mode" value="document"><h2>Identity document</h2>
<p class="muted">Upload a photo or PDF scan of a passport data page or an ID card (front, and back when the MRZ
is on the back). The same checks as the app's document scan run before any credential is offered.</p>
<label>Passport data page / ID card front (image or PDF)</label><input type="file" name="front" required
accept="image/*,application/pdf"><label>ID card back (optional)</label><input type="file" name="back"
accept="image/*,application/pdf"><label>Document type</label><select name="document_kind">
<option value="">Detect automatically</option><option value="passport">Passport</option>
<option value="id_card">ID card</option></select>{common}</form>"""
    body = f"""<h1>Issue a PID or attestation to a wallet</h1>
<p class="muted">Creates an OpenID4VCI credential offer (pre-authorized code flow). Scan the QR code with
getYourID Wallet, or any EUDI-compatible wallet that accepts this test issuer.</p>{tabs}{form}
<p class="muted">Issuer metadata: <a href="/.well-known/openid-credential-issuer">/.well-known/openid-credential-issuer</a>
· IACA: <a href="/pki/iaca.pem">iaca.pem</a></p>"""
    return _page("getYourID Issuer", body)


def offer_page(offer_id: str, offer_uri: str, tx_code: str | None, names: list[str], holder: str,
               report_lines: list[str]) -> str:
    qr = segno.make(offer_uri, error="m").svg_inline(scale=6, dark="#192C70", border=2)
    tx = (f'<p>Transaction code</p><div class="code">{tx_code}</div>'
          f'<p class="muted">Give this code to the holder separately; the wallet asks for it.</p>'
          if tx_code else '<p class="muted">No transaction code required.</p>')
    report = "".join(f"<li>{html.escape(r)}</li>" for r in report_lines)
    body = f"""<h1>Offer ready</h1><div class="card"><div class="qr">{qr}<div>
<p><b>{html.escape(holder)}</b></p><p>{html.escape(", ".join(names))}</p>{tx}
<p>Status: <b id="st">waiting for the wallet…</b></p></div></div>
<p class="muted">On the phone itself: <a href="{html.escape(offer_uri)}">open in wallet</a></p>
<p class="muted">Offer URI: <code>{html.escape(offer_uri)}</code></p></div>
{f'<div class="card"><h2>Verification</h2><ul class="report">{report}</ul></div>' if report else ''}
<p><a href="/issuer">Create another offer</a></p>
<script>
const label={{offered:"waiting for the wallet…",token_issued:"wallet connected, issuing…",
issued:"✓ credential(s) delivered to the wallet",blocked:"✗ blocked after wrong transaction codes",
expired:"offer expired"}};
async function poll(){{try{{const r=await fetch("/oid4vci/offers/{offer_id}/status");const j=await r.json();
const el=document.getElementById("st");el.textContent=label[j.status]||j.status;
el.className=j.status==="issued"?"ok":(j.status==="blocked"||j.status==="expired"?"bad":"");
if(j.status==="issued"||j.status==="blocked"||j.status==="expired")return;}}catch(e){{}}setTimeout(poll,2000);}}
poll();
</script>"""
    return _page("Credential offer", body)


def error_page(message: str, report_lines: list[str] | None = None) -> str:
    report = "".join(f"<li>{html.escape(r)}</li>" for r in (report_lines or []))
    body = f"""<h1>Could not create the offer</h1><div class="card"><p class="bad">{html.escape(message)}</p>
{f'<ul class="report">{report}</ul>' if report else ''}</div><p><a href="/issuer">Back</a></p>"""
    return _page("Offer failed", body)
