#!/usr/bin/env python3
"""Static i18n build: English index.html (template) -> dist/index.html + dist/<locale>/index.html
Usage: python3 build.py [src.html] [outdir]"""
import sys, os, re, json, html
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from i18n import LOCALES, T

SRC = sys.argv[1] if len(sys.argv) > 1 else "/mnt/user-data/uploads/index.html"
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "dist")
BASE = "https://tixyq.github.io/opencode-android/"
en = T["en"]
src = open(SRC, encoding="utf-8").read()
E = lambda s: html.escape(s, quote=True)


def once(old, new, s):
    assert s.count(old) == 1, (s.count(old), old[:70])
    return s.replace(old, new)


# ---------- 1. turn the English page into a template ----------
t = src
rep = [
    ("<h1>%s</h1>" % en["h1"], "<h1>{{h1}}</h1>"),
    (">%s<!--/-->" % en["hero1"], ">{{hero1}}<!--/-->"),
    (">%s<!--/--></p>" % en["hero2"], ">{{hero2}}<!--/--></p>"),
    ("</svg>%s</a>" % en["download"], "</svg>{{download}}</a>"),
    ('alt="%s"' % en["alt"], 'alt="{{alt}}"'),
    ("<h3>%s</h3>" % en["what1_h"], "<h3>{{what1_h}}</h3>"),
    ("<p>%s</p>" % en["what1_p"], "<p>{{what1_p}}</p>"),
    ("<h3>%s</h3>" % en["what2_h"], "<h3>{{what2_h}}</h3>"),
    ("<p>%s</p>" % en["what2_p"], "<p>{{what2_p}}</p>"),
    ("<strong>%s</strong> %s" % (en["f1_t"], en["f1_d"]), "<strong>{{f1_t}}</strong> {{f1_d}}"),
    ("<strong>%s</strong> %s" % (en["f2_t"], en["f2_d"]), "<strong>{{f2_t}}</strong> {{f2_d}}"),
    ("<strong>%s</strong> %s" % (en["f3_t"], en["f3_d"]), "<strong>{{f3_t}}</strong> {{f3_d}}"),
    ("<h3>%s</h3>" % en["priv_h"], "<h3>{{priv_h}}</h3>"),
    ("<!--$-->%s</p>" % en["priv_p"], "<!--$-->{{priv_p}}</p>"),
    ('<span data-slot="platform">%s</span>' % en["platform"], '<span data-slot="platform">{{platform}}</span>'),
    ("<p>%s</p></div></footer>" % en["disclaimer"], "{{picker}}<p>{{disclaimer}}</p></div></footer>"),
]
for o, n in rep:
    t = once(o, n, t)

desc_attr = 'content="%s"' % en["desc"]
assert t.count(desc_attr) == 3
t = t.replace(desc_attr, 'content="{{desc}}"')
t = t.replace('content="OpenCode for Android: home, new session and chat screens"', 'content="{{alt}}"')
t = t.replace('aria-label="OpenCode for Android"', 'aria-label="{{title}}"')
t = once("<title>OpenCode for Android</title>", "<title>{{title}}</title>", t)
for tag in ('<meta property="og:site_name" content="%s">', '<meta property="og:title" content="%s">',
            '<meta name="twitter:title" content="%s">', '<meta name="application-name" content="%s">',
            '<meta name="apple-mobile-web-app-title" content="%s">'):
    t = once(tag % "OpenCode for Android", tag % "{{title}}", t)
t = once('<html lang="en" dir="ltr" data-locale="en">', '<html lang="{{lang}}" dir="{{dir}}" data-locale="{{code}}">', t)
t = once('<link rel="canonical" href="https://tixyq.github.io/opencode-android/">',
         '<link rel="canonical" href="{{url}}">\n{{alternates}}', t)
t = once('<meta property="og:locale" content="en_US">', '<meta property="og:locale" content="{{og_locale}}">', t)
t = once('<meta property="og:url" content="https://tixyq.github.io/opencode-android/">',
         '<meta property="og:url" content="{{url}}">', t)
m = re.search(r'<script type="application/ld\+json">(.*?)</script>', t, re.S)
t = t.replace(m.group(0), '<script type="application/ld+json">{{jsonld}}</script>')
LD = json.loads(m.group(1))

left = re.findall(r".{40}(?:for Android|OpenCode\?).{20}", re.sub(r"data:[^\"')]+", "", t))
print("untranslated English leftovers:", left)

# ---------- 2. shared snippets ----------
FALLBACK = '"Berkeley Mono","IBM Plex Mono",ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,"Liberation Mono","Courier New",monospace,%s,sans-serif'
FONTS = {
    "ar": '"Noto Sans Arabic","Segoe UI",Tahoma', "th": '"Noto Sans Thai","Leelawadee UI",Tahoma',
    "ja": '"Hiragino Sans","Yu Gothic","Noto Sans JP"', "ko": '"Apple SD Gothic Neo","Malgun Gothic","Noto Sans KR"',
    "zh": '"PingFang SC","Microsoft YaHei","Noto Sans SC"', "zht": '"PingFang TC","Microsoft JhengHei","Noto Sans TC"',
}
font_css = "\n".join(':root[data-locale="%s"]{--font-mono:%s}' % (k, FALLBACK % v) for k, v in FONTS.items())

PK = '[data-component="footer"][data-variant="links"] [data-slot="cell"] [data-component="language-picker"]'
CSS = """<style id="i18n">
%s
%s{width:auto;height:auto;margin-top:12px}
%s [data-component="dropdown"]{width:auto}
%s [data-slot="trigger"]{width:auto;padding:6px 12px;border-radius:var(--border-radius-sm);font-size:13px;color:var(--color-text-weak)}
%s [data-slot="trigger"]:hover{color:var(--color-text);background:var(--color-background-weak);text-decoration:none}
[data-component="language-picker"] [data-slot="trigger"] span{flex:0 0 auto}
[data-component="language-picker"] [data-slot="dropdown"]{left:50%%;right:auto;transform:translateX(-50%%);min-width:200px;padding:4px 0}
[data-component="language-picker"] [data-slot="dropdown"][hidden]{display:none}
[data-component="language-picker"] [data-slot="item"]{text-align:start;text-decoration:none;box-sizing:border-box;line-height:1.4}
[data-component="language-picker"] [data-slot="item"]:hover{text-decoration:none}
[data-component="language-picker"] [data-slot="chevron"]{transition:transform .15s ease}
[data-component="language-picker"] [aria-expanded="true"] [data-slot="chevron"]{transform:rotate(180deg)}
</style>""" % (font_css, PK, PK, PK, PK)

DETECT = """<script>(function(){try{var P=location.pathname.replace(/index\\.html$/,""),S=null;try{S=localStorage.getItem("oc_locale")}catch(e){}
var L=%s;function go(c){if(c&&c!=="en"&&L.indexOf(c)>-1){location.replace(P+c+"/"+location.search+location.hash)}}
if(S){go(S);return}
var l=navigator.languages&&navigator.languages.length?navigator.languages:[navigator.language||""];
for(var i=0;i<l.length;i++){var x=String(l[i]).toLowerCase(),b=x.split("-")[0],c=null;
if(b==="en")return;
if(b==="zh")c=/^zh-(tw|hk|mo|hant)/.test(x)?"zht":"zh";else if(b==="nb"||b==="nn"||b==="no")c="no";else if(b==="pt")c="br";else if(L.indexOf(b)>-1)c=b;
if(c){go(c);return}}}catch(e){}})()</script>"""

PICKER_JS = """<script>(function(){var p=document.querySelector('[data-component="language-picker"]');if(!p)return;
var b=p.querySelector('[data-slot="trigger"]'),d=p.querySelector('[data-slot="dropdown"]');
function o(v){d.hidden=!v;b.setAttribute("aria-expanded",v?"true":"false")}
b.addEventListener("click",function(e){e.stopPropagation();o(d.hidden)});
document.addEventListener("click",function(e){if(!p.contains(e.target))o(false)});
document.addEventListener("keydown",function(e){if(e.key==="Escape"){o(false);b.focus()}});
p.addEventListener("click",function(e){var a=e.target.closest("a[data-locale]");if(a){try{localStorage.setItem("oc_locale",a.getAttribute("data-locale"))}catch(x){}}});
})()</script>"""

CHEV = ('<svg data-slot="chevron" width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">'
        '<path d="M3 4.5L6 7.5L9 4.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>')


def href_to(cur, code):
    """Relative link from page <cur> to page <code> (works on any host / sub-path)."""
    if cur == "en":
        return "./" if code == "en" else code + "/"
    return "../" if code == "en" else "../" + code + "/"


def picker(cur):
    items = "".join(
        '<a data-slot="item" data-locale="%s" lang="%s" hreflang="%s" href="%s"%s>%s</a>' % (
            c, LOCALES[c][1], LOCALES[c][1], href_to(cur, c),
            ' data-selected="true" aria-current="true"' if c == cur else "", E(LOCALES[c][0]))
        for c in LOCALES)
    return ('<div data-component="language-picker"><div data-component="dropdown">'
            '<button type="button" data-slot="trigger" aria-haspopup="true" aria-expanded="false" aria-label="%s">'
            '<span>%s</span>%s</button><div data-slot="dropdown" data-align="left" hidden>%s</div></div></div>'
            % (E(T[cur]["lang_label"]), E(LOCALES[cur][0]), CHEV, items))


def url_of(code):
    return BASE if code == "en" else BASE + code + "/"


ALTS = "\n".join('<link rel="alternate" hreflang="%s" href="%s">' % (LOCALES[c][1], url_of(c)) for c in LOCALES)
ALTS += '\n<link rel="alternate" hreflang="x-default" href="%s">' % BASE

# ---------- 3. render ----------
os.makedirs(OUT, exist_ok=True)
for code, (name, lang, ogl, d) in LOCALES.items():
    v = dict(T[code])
    v["title"] = "OpenCode " + v["platform"]
    s = t
    ld = dict(LD, name=v["title"], description=v["desc"], inLanguage=lang, url=url_of(code))
    extra = {
        "lang": lang, "dir": d, "code": code, "og_locale": ogl, "url": url_of(code), "alternates": ALTS,
        "jsonld": json.dumps(ld, ensure_ascii=False).replace("</", "<\\/"), "picker": picker(code),
    }
    for k, val in v.items():
        s = s.replace("{{%s}}" % k, E(val))
    for k, val in extra.items():
        s = s.replace("{{%s}}" % k, val)
    head_extra = CSS + ("\n" + DETECT % json.dumps([c for c in LOCALES if c != "en"]) if code == "en" else "")
    s = once("</head>", head_extra + "</head>", s)
    s = once("</body>", PICKER_JS + "</body>", s)
    leftovers = re.findall(r"\{\{\w+\}\}", s)
    assert not leftovers, (code, leftovers)
    path = os.path.join(OUT, "index.html" if code == "en" else os.path.join(code, "index.html"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w", encoding="utf-8").write(s)

sm = ['<?xml version="1.0" encoding="UTF-8"?>',
      '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">']
alts = "".join('<xhtml:link rel="alternate" hreflang="%s" href="%s"/>' % (LOCALES[x][1], url_of(x)) for x in LOCALES)
alts += '<xhtml:link rel="alternate" hreflang="x-default" href="%s"/>' % BASE
for c in LOCALES:
    sm.append("<url><loc>%s</loc>%s</url>" % (url_of(c), alts))
sm.append("</urlset>")
open(os.path.join(OUT, "sitemap.xml"), "w").write("\n".join(sm) + "\n")
print("built", len(LOCALES), "locales ->", OUT)
