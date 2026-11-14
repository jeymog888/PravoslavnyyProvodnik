package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.security.MessageDigest
import java.util.IdentityHashMap

/**
 * Closed technical envelope for the reviewed linked index, never an authority issuer.
 * The caller must first bound and audit the same raw response and validate every whole
 * image tuple/derivative. Only those exact elements and attributes may enter [matches].
 * Reference: 31095 original UTF-8 bytes, SHA-256
 * 8cf4d0a03195b2ae36256082a4c2168b84132f0f1f11ed7a608c7e7307873c0d
 */
internal object LinkedAttachmentIndexTemplate {
    private const val MAX_NODES = 1_600
    private const val MAX_DEPTH = 64
    private const val MAX_BYTES = 262_144
    private val tokens = setOf("IMAGE_1", "EMPTY_1", "IMAGE_2", "EMPTY_2", "IMAGE_3", "EMPTY_3", "OG_IMAGE_1")
    private val reference: Document by lazy { Jsoup.parse(REFERENCE_HTML) }

    /** No page mutation, URL replacement, attribute allowlist, or ignored subtree. */
    fun matches(page: Document, slots: Map<Element, Map<String, String>>): Boolean {
        if (slots.size != 7 || slots.values.sumOf { it.size } != 13) return false
        val masks = IdentityHashMap<Element, Map<String, String>>()
        for ((element, attributes) in slots) {
            if (attributes.isEmpty() || attributes.any { (key, value) ->
                    key.isEmpty() || !element.hasAttr(key) || value !in tokens
                }) return false
            masks[element] = attributes.toMap()
        }
        var count = 0
        fun bounded(node: Node, depth: Int): Boolean {
            if (depth > MAX_DEPTH || ++count > MAX_NODES) return false
            return node.childNodes().all { bounded(it, depth + 1) }
        }
        if (!bounded(page, 0)) return false
        var used = 0
        fun same(expected: Node, actual: Node): Boolean {
            // CData, declarations, and unknown node kinds cannot masquerade as text.
            if (expected.javaClass != actual.javaClass) return false
            when (expected) {
                is Element -> {
                    actual as Element
                    if (expected.tagName() != actual.tagName() ||
                        expected.tag().namespace() != actual.tag().namespace()) return false
                    val wanted = expected.attributes().asList().associate { it.key to it.value }
                    val found = actual.attributes().asList().associate { it.key to it.value }
                    if (wanted.keys != found.keys) return false
                    val mask = masks[actual]
                    if (mask != null && mask.keys.any { it !in wanted }) return false
                    for ((key, value) in wanted) {
                        if (value in tokens) {
                            // Every reference slot must be masked at this exact DOM position.
                            if (mask?.get(key) != value) return false
                            used++
                        } else {
                            if (mask?.containsKey(key) == true || found[key] != value) return false
                        }
                    }
                }
                is TextNode -> if (spacing(expected.wholeText) != spacing((actual as TextNode).wholeText)) return false
                is DataNode -> if (expected.wholeData != (actual as DataNode).wholeData) return false
                is Comment -> if (expected.data != (actual as Comment).data) return false
                is DocumentType -> {
                    actual as DocumentType
                    if (expected.attributes().asList().associate { it.key to it.value } !=
                        actual.attributes().asList().associate { it.key to it.value }) return false
                }
                else -> return false
            }
            val left = expected.childNodes().filterNot(::spacingOnly)
            val right = actual.childNodes().filterNot(::spacingOnly)
            return left.size == right.size && left.indices.all { same(left[it], right[it]) }
        }
        return same(reference, page) && used == 13
    }

    /** Pin all raw bodies in order, including the 13 empty external script bodies. */
    fun rawBodiesMatch(source: String): Boolean {
        if (source.length > MAX_BYTES || source.toByteArray(Charsets.UTF_8).size > MAX_BYTES || "&#" in source) return false
        return bodiesMatch(source, scriptBodies, scriptHashes) && bodiesMatch(source, styleBodies, styleHashes) &&
            rawStructure(source)?.let { it == referenceStructure } == true
    }

    private val referenceStructure by lazy { requireNotNull(rawStructure(REFERENCE_HTML)) }

    // Jsoup can discard duplicate attributes or stray closing tags. Account for the raw
    // token boundaries as well, before comparing its DOM. Code bodies are pinned above.
    private fun rawStructure(source: String): List<String>? {
        val result = mutableListOf<String>()
        var at = 0
        fun spacing(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000c'
        fun name(c: Char) = c.isLetterOrDigit() && c.code < 128 || c in "-:_"
        while (true) {
            at = source.indexOf('<', at).takeIf { it >= 0 } ?: break
            if (result.size >= MAX_NODES) return null
            if (source.startsWith("<!--", at)) {
                val end = source.indexOf("-->", at + 4); if (end < 0) return null
                result += "comment"; at = end + 3; continue
            }
            if (source.startsWith("<!DOCTYPE html>", at, ignoreCase = true)) {
                result += "doctype:html"; at += "<!DOCTYPE html>".length; continue
            }
            at++
            val closing = at < source.length && source[at] == '/'
            if (closing) at++
            val start = at
            while (at < source.length && name(source[at])) at++
            if (start == at) return null
            val tag = source.substring(start, at).lowercase(java.util.Locale.ROOT)
            val keys = mutableSetOf<String>()
            while (true) {
                while (at < source.length && spacing(source[at])) at++
                if (at >= source.length) return null
                if (source[at] == '>') { at++; break }
                if (source.startsWith("/>", at)) { if (closing) return null; at += 2; break }
                if (closing) return null
                val keyStart = at
                while (at < source.length && name(source[at])) at++
                if (keyStart == at || !keys.add(source.substring(keyStart, at).lowercase(java.util.Locale.ROOT))) return null
                while (at < source.length && spacing(source[at])) at++
                if (at < source.length && source[at] == '=') {
                    at++; while (at < source.length && spacing(source[at])) at++
                    if (at >= source.length) return null
                    val quote = source[at].takeIf { it == '\'' || it == '"' }
                    if (quote != null) {
                        at++; val end = source.indexOf(quote, at); if (end < 0) return null
                        at = end + 1
                    } else {
                        val valueStart = at
                        while (at < source.length && !spacing(source[at]) && source[at] != '>') {
                            if (source[at] in "<\"'`=") return null
                            at++
                        }
                        if (valueStart == at) return null
                    }
                }
            }
            result += (if (closing) "end:" else "start:") + tag + ":" + keys.sorted().joinToString(",")
            if (!closing && tag in setOf("script", "style")) {
                val end = Regex("</" + tag + "\\s*>", RegexOption.IGNORE_CASE).find(source, at) ?: return null
                at = end.range.first // Read the matching raw closing token on the next iteration.
            }
        }
        return result
    }

    private fun bodiesMatch(source: String, pattern: Regex, expected: List<String>): Boolean {
        val bodies = pattern.findAll(source).iterator()
        for (hash in expected) {
            if (!bodies.hasNext() || sha(bodies.next().groupValues[1]) != hash) return false
        }
        return !bodies.hasNext()
    }

    // Only these five predeclared spacing characters may collapse. Other controls,
    // format characters, entity payloads, and Unicode spaces remain visible to comparison.
    private fun isSpacing(value: Char): Boolean = value == ' ' || value == '\t' || value == '\r' ||
        value == '\n' || value == '\u00a0'
    private fun spacingOnly(node: Node): Boolean = node.javaClass == TextNode::class.java &&
        (node as TextNode).wholeText.all(::isSpacing)
    private fun spacing(value: String): String = buildString {
        var pending = false
        for (character in value) {
            if (isSpacing(character)) pending = isNotEmpty()
            else {
                if (pending) append(' ')
                append(character)
                pending = false
            }
        }
    }
    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private val scriptBodies = Regex("<script\\b[^>]*>(.*?)</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val styleBodies = Regex("<style\\b[^>]*>(.*?)</style\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val scriptHashes = listOf(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "af5b7bdd2fab0b0d66fce9982bc63b8ab2e0a3a676b622b1148ec371e2ea939d",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "7633dd4f927592e585c1ac0386d6f3cf4607b7feb78b7094046dc2c948cb6b87",
        "66fad982718f5011b056b6702e565bcdc001850302dbb5b0b2c71df065bb1595",
        "2669838200f850e56e865279c875136fd9ee0b19cc3014750235f5ebb9426fdd",
        "1b4a3a2f4518ed635c4469ea7c6770b88d37c49422c2a8b88b01614fc8818c33",
        "a2f8a564f46507dcc2dbe21fe5032c3ad4ee872c5b4815fcea8ab844f2b69a85",
        "0df7919c07ae926ff23ef3e7d080bdbc10f11e554f436d3a39e541a424cc456e",
        "fe65ea31cf820e249984100e8a3dddbbf176feb636398ca87a54f687a5e9efb8"
    )
    private val styleHashes = listOf(
        "c9d1b0ba82d4d3fe4a072037b33cf9a82a9f32c63439071fe9067f9d9a9217a4",
        "e5113464471a0f98ba8545d5a4797dc14cc617f65efea6e3ef1f3822880345e2",
        "5f0bbc6c6a20cac3843b27f74a267cb33776ba8434ea6e1a626a0e170baaf8e6",
        "b71e540a299eaf17f65c064ab91cebeaff93165187ccbe33d471889a7e91cc3d",
        "f1478c907c15e4fa81df7c50b4bae4c7742184723ce67461c690a4221942c3dc",
        "daf5724ac6d86715755715be1b2b0088cd6b40a2c38b98b4eda9f5d213efec48"
    )

    // Generated by 13 exact raw attribute substitutions; script/style bytes are untouched.
    private const val REFERENCE_HTML = "<!DOCTYPE html> <html> <head> <meta charset=\"utf-8\" /> <meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" /> <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" /> <!--metatextblock--> <title>Расписание Богослужений</title> <meta name=\"description\" content=\"/schedule\" /> <meta property=\"og:url\" content=\"http://xn----7sbbgncsdakjgxi3cgfp8d.xn--p1ai/schedule\" /> <meta property=\"og:title\" content=\"Расписание Богослужений\" /> <meta property=\"og:description\" content=\"/schedule\" /> <meta property=\"og:type\" content=\"website\" /> <meta property=\"og:image\" content=\"OG_IMAGE_1\" /> <link rel=\"canonical\" href=\"http://xn----7sbbgncsdakjgxi3cgfp8d.xn--p1ai/schedule\"> <!--/metatextblock--> <meta name=\"format-detection\" content=\"telephone=no\" /> <meta http-equiv=\"x-dns-prefetch-control\" content=\"on\"> <link rel=\"dns-prefetch\" href=\"https://ws.tildacdn.com\"> <link rel=\"dns-prefetch\" href=\"https://static.tildacdn.com\"> <!--favicons--> <link rel=\"shortcut icon\" href=\"https://static.tildacdn.com/img/tildafavicon.ico\" type=\"image/x-icon\"/> <!--/favicons--> <!-- Assets --> <script src=\"https://neo.tildacdn.com/js/tilda-fallback-1.0.min.js\" async charset=\"utf-8\"></script> <link rel=\"stylesheet\" href=\"https://static.tildacdn.com/css/tilda-grid-3.0.min.css\" type=\"text/css\" media=\"all\" onerror=\"this.loaderr='y';\"/> <link rel=\"stylesheet\" href=\"https://static.tildacdn.com/ws/project5957836/tilda-blocks-page30438951.min.css?t=1793729350\" type=\"text/css\" media=\"all\" onerror=\"this.loaderr='y';\" /> <link rel=\"preconnect\" href=\"https://fonts.gstatic.com\"> <link href=\"https://fonts.googleapis.com/css2?family=Roboto:wght@300;400;500;700&subset=latin,cyrillic\" rel=\"stylesheet\"> <link rel=\"stylesheet\" href=\"https://static.tildacdn.com/css/tilda-menusub-1.0.min.css\" type=\"text/css\" media=\"print\" onload=\"this.media='all';\" onerror=\"this.loaderr='y';\" /> <noscript><link rel=\"stylesheet\" href=\"https://static.tildacdn.com/css/tilda-menusub-1.0.min.css\" type=\"text/css\" media=\"all\" /></noscript> <link rel=\"stylesheet\" href=\"https://static.tildacdn.com/css/tilda-menu-widgeticons-1.0.min.css\" type=\"text/css\" media=\"all\" onerror=\"this.loaderr='y';\" /> <link rel=\"stylesheet\" href=\"https://static.tildacdn.com/css/tilda-zoom-2.0.min.css\" type=\"text/css\" media=\"print\" onload=\"this.media='all';\" onerror=\"this.loaderr='y';\" /> <noscript><link rel=\"stylesheet\" href=\"https://static.tildacdn.com/css/tilda-zoom-2.0.min.css\" type=\"text/css\" media=\"all\" /></noscript> <script nomodule src=\"https://static.tildacdn.com/js/tilda-polyfill-1.0.min.js\" charset=\"utf-8\"></script> <script type=\"text/javascript\">function t_onReady(func) {if(document.readyState!='loading') {func();} else {document.addEventListener('DOMContentLoaded',func);}}\nfunction t_onFuncLoad(funcName,okFunc,time) {if(typeof window[funcName]==='function') {okFunc();} else {setTimeout(function() {t_onFuncLoad(funcName,okFunc,time);},(time||100));}}function t_throttle(fn,threshhold,scope) {return function() {fn.apply(scope||this,arguments);};}</script> <script src=\"https://static.tildacdn.com/js/jquery-1.10.2.min.js\" charset=\"utf-8\" onerror=\"this.loaderr='y';\"></script> <script src=\"https://static.tildacdn.com/js/tilda-scripts-3.0.min.js\" charset=\"utf-8\" defer onerror=\"this.loaderr='y';\"></script> <script src=\"https://static.tildacdn.com/ws/project5957836/tilda-blocks-page30438951.min.js?t=1793729350\" charset=\"utf-8\" async onerror=\"this.loaderr='y';\"></script> <script src=\"https://static.tildacdn.com/js/tilda-lazyload-1.0.min.js\" charset=\"utf-8\" async onerror=\"this.loaderr='y';\"></script> <script src=\"https://static.tildacdn.com/js/hammer.min.js\" charset=\"utf-8\" async onerror=\"this.loaderr='y';\"></script> <script src=\"https://static.tildacdn.com/js/tilda-menusub-1.0.min.js\" charset=\"utf-8\" async onerror=\"this.loaderr='y';\"></script> <script src=\"https://static.tildacdn.com/js/tilda-menu-1.0.min.js\" charset=\"utf-8\" async onerror=\"this.loaderr='y';\"></script> <script src=\"https://static.tildacdn.com/js/tilda-menu-widgeticons-1.0.min.js\" charset=\"utf-8\" async onerror=\"this.loaderr='y';\"></script> <script src=\"https://static.tildacdn.com/js/tilda-zoom-2.0.min.js\" charset=\"utf-8\" async onerror=\"this.loaderr='y';\"></script> <script src=\"https://static.tildacdn.com/js/tilda-skiplink-1.0.min.js\" charset=\"utf-8\" async onerror=\"this.loaderr='y';\"></script> <script src=\"https://static.tildacdn.com/js/tilda-events-1.0.min.js\" charset=\"utf-8\" async onerror=\"this.loaderr='y';\"></script> <script type=\"text/javascript\">window.dataLayer=window.dataLayer||[];</script> <script type=\"text/javascript\">(function() {var ttt='cebo';var tt='oog';var re=new RegExp(\"bot|g\" + tt + \"le|yandex|baidu|bing|msn|duckduckbot|teoma|slurp|crawler|spider|robot|crawling|fa\" + ttt + \"ok|lighthouse\",\"i\");if((re.test(navigator.userAgent))===false&&typeof(sessionStorage)!='undefined'&&sessionStorage.getItem('visited')!=='y'&&document.visibilityState){var style=document.createElement('style');style.type='text/css';style.innerHTML='@media screen and (min-width: 980px) {.t-records {opacity: 0;}.t-records_animated {-webkit-transition: opacity ease-in-out .2s;-moz-transition: opacity ease-in-out .2s;-o-transition: opacity ease-in-out .2s;transition: opacity ease-in-out .2s;}.t-records.t-records_visible {opacity: 1;}}';document.getElementsByTagName('head')[0].appendChild(style);function t_setvisRecs(){var alr=document.querySelectorAll('.t-records');Array.prototype.forEach.call(alr,function(el) {el.classList.add(\"t-records_animated\");});setTimeout(function() {Array.prototype.forEach.call(alr,function(el) {el.classList.add(\"t-records_visible\");});sessionStorage.setItem(\"visited\",\"y\");},400);}\ndocument.addEventListener('DOMContentLoaded',t_setvisRecs);}})();</script></head> <body class=\"t-body\" style=\"margin:0;\"> <!--allrecords--> <div id=\"allrecords\" class=\"t-records\" data-hook=\"blocks-collection-content-node\" data-tilda-project-id=\"5957836\" data-tilda-page-id=\"30438951\" data-tilda-page-alias=\"schedule\" data-tilda-formskey=\"6063156a0c93b67ed1b1a3c135957836\" data-tilda-lazy=\"yes\" data-tilda-root-zone=\"com\" data-tilda-project-country=\"RU\"> <!--header--> <header id=\"t-header\" class=\"t-records\" data-hook=\"blocks-collection-content-node\" data-tilda-project-id=\"5957836\" data-tilda-page-id=\"30457218\" data-tilda-page-alias=\"my-menu\" data-tilda-formskey=\"6063156a0c93b67ed1b1a3c135957836\" data-tilda-lazy=\"yes\" data-tilda-root-zone=\"com\" data-tilda-project-country=\"RU\"> <div id=\"rec492947799\" class=\"r t-rec t-rec_pt_0 t-rec_pb_0\" style=\"padding-top:0px;padding-bottom:0px; \" data-animationappear=\"off\" data-record-type=\"450\"> <div class=\"t450__menu__content \"\nstyle=\"top:15px; \" data-menu=\"yes\" data-appearoffset=\"\" data-hideoffset=\"\"> <div class=\"t450__burger_container t450__small \"> <div class=\"t450__burger_bg t450__burger_cirqle\"\nstyle=\"; opacity:0.50;\"> </div> <button type=\"button\"\nclass=\"t-menuburger t-menuburger_first t-menuburger__small\"\naria-label=\"Навигационное меню\"\naria-expanded=\"false\"> <span style=\"background-color:#ffffff;\"></span> <span style=\"background-color:#ffffff;\"></span> <span style=\"background-color:#ffffff;\"></span> <span style=\"background-color:#ffffff;\"></span> </button> <script>function t_menuburger_init(recid) {var rec=document.querySelector('#rec' + recid);if(!rec) return;var burger=rec.querySelector('.t-menuburger');if(!burger) return;var isSecondStyle=burger.classList.contains('t-menuburger_second');if(isSecondStyle&&!window.isMobile&&!('ontouchend' in document)) {burger.addEventListener('mouseenter',function() {if(burger.classList.contains('t-menuburger-opened')) return;burger.classList.remove('t-menuburger-unhovered');burger.classList.add('t-menuburger-hovered');});burger.addEventListener('mouseleave',function() {if(burger.classList.contains('t-menuburger-opened')) return;burger.classList.remove('t-menuburger-hovered');burger.classList.add('t-menuburger-unhovered');setTimeout(function() {burger.classList.remove('t-menuburger-unhovered');},300);});}\nburger.addEventListener('click',function() {if(!burger.closest('.tmenu-mobile')&&!burger.closest('.t450__burger_container')&&!burger.closest('.t3112__burger_container')&&!burger.closest('.t466__container')&&!burger.closest('.t204__burger')&&!burger.closest('.t199__js__menu-toggler')) {burger.classList.toggle('t-menuburger-opened');burger.classList.remove('t-menuburger-unhovered');}});var menu=rec.querySelector('[data-menu=\"yes\"]');if(!menu) return;var menuLinks=menu.querySelectorAll('.t-menu__link-item');var submenuClassList=['t978__menu-link_hook','t978__tm-link','t966__tm-link','t794__tm-link','t-menusub__target-link'];Array.prototype.forEach.call(menuLinks,function(link) {link.addEventListener('click',function() {var isSubmenuHook=submenuClassList.some(function(submenuClass) {return link.classList.contains(submenuClass);});if(isSubmenuHook) return;burger.classList.remove('t-menuburger-opened');});});menu.addEventListener('clickedAnchorInTooltipMenu',function() {burger.classList.remove('t-menuburger-opened');});}\nt_onReady(function() {t_onFuncLoad('t_menuburger_init',function(){t_menuburger_init('492947799');});});</script> <style>.t-menuburger{position:relative;flex-shrink:0;width:28px;height:20px;padding:0;border:none;background-color:transparent;outline:none;-webkit-transform:rotate(0deg);transform:rotate(0deg);transition:transform .5s ease-in-out;cursor:pointer;z-index:999;}.t-menuburger span{display:block;position:absolute;width:100%;opacity:1;left:0;-webkit-transform:rotate(0deg);transform:rotate(0deg);transition:.25s ease-in-out;height:3px;background-color:#000;}.t-menuburger span:nth-child(1){top:0px;}.t-menuburger span:nth-child(2),.t-menuburger span:nth-child(3){top:8px;}.t-menuburger span:nth-child(4){top:16px;}.t-menuburger__big{width:42px;height:32px;}.t-menuburger__big span{height:5px;}.t-menuburger__big span:nth-child(2),.t-menuburger__big span:nth-child(3){top:13px;}.t-menuburger__big span:nth-child(4){top:26px;}.t-menuburger__small{width:22px;height:14px;}.t-menuburger__small span{height:2px;}.t-menuburger__small span:nth-child(2),.t-menuburger__small span:nth-child(3){top:6px;}.t-menuburger__small span:nth-child(4){top:12px;}.t-menuburger-opened span:nth-child(1){top:8px;width:0%;left:50%;}.t-menuburger-opened span:nth-child(2){-webkit-transform:rotate(45deg);transform:rotate(45deg);}.t-menuburger-opened span:nth-child(3){-webkit-transform:rotate(-45deg);transform:rotate(-45deg);}.t-menuburger-opened span:nth-child(4){top:8px;width:0%;left:50%;}.t-menuburger-opened.t-menuburger__big span:nth-child(1){top:6px;}.t-menuburger-opened.t-menuburger__big span:nth-child(4){top:18px;}.t-menuburger-opened.t-menuburger__small span:nth-child(1),.t-menuburger-opened.t-menuburger__small span:nth-child(4){top:6px;}@media (hover),(min-width:0\\0){.t-menuburger_first:hover span:nth-child(1){transform:translateY(1px);}.t-menuburger_first:hover span:nth-child(4){transform:translateY(-1px);}.t-menuburger_first.t-menuburger__big:hover span:nth-child(1){transform:translateY(3px);}.t-menuburger_first.t-menuburger__big:hover span:nth-child(4){transform:translateY(-3px);}}.t-menuburger_second span:nth-child(2),.t-menuburger_second span:nth-child(3){width:80%;left:20%;right:0;}@media (hover),(min-width:0\\0){.t-menuburger_second.t-menuburger-hovered span:nth-child(2),.t-menuburger_second.t-menuburger-hovered span:nth-child(3){animation:t-menuburger-anim 0.3s ease-out normal forwards;}.t-menuburger_second.t-menuburger-unhovered span:nth-child(2),.t-menuburger_second.t-menuburger-unhovered span:nth-child(3){animation:t-menuburger-anim2 0.3s ease-out normal forwards;}}.t-menuburger_second.t-menuburger-opened span:nth-child(2),.t-menuburger_second.t-menuburger-opened span:nth-child(3){left:0;right:0;width:100%!important;}.t-menuburger_third span:nth-child(4){width:70%;left:unset;right:0;}@media (hover),(min-width:0\\0){.t-menuburger_third:not(.t-menuburger-opened):hover span:nth-child(4){width:100%;}}.t-menuburger_third.t-menuburger-opened span:nth-child(4){width:0!important;right:50%;}.t-menuburger_fourth{height:12px;}.t-menuburger_fourth.t-menuburger__small{height:8px;}.t-menuburger_fourth.t-menuburger__big{height:18px;}.t-menuburger_fourth span:nth-child(2),.t-menuburger_fourth span:nth-child(3){top:4px;opacity:0;}.t-menuburger_fourth span:nth-child(4){top:8px;}.t-menuburger_fourth.t-menuburger__small span:nth-child(2),.t-menuburger_fourth.t-menuburger__small span:nth-child(3){top:3px;}.t-menuburger_fourth.t-menuburger__small span:nth-child(4){top:6px;}.t-menuburger_fourth.t-menuburger__small span:nth-child(2),.t-menuburger_fourth.t-menuburger__small span:nth-child(3){top:3px;}.t-menuburger_fourth.t-menuburger__small span:nth-child(4){top:6px;}.t-menuburger_fourth.t-menuburger__big span:nth-child(2),.t-menuburger_fourth.t-menuburger__big span:nth-child(3){top:6px;}.t-menuburger_fourth.t-menuburger__big span:nth-child(4){top:12px;}@media (hover),(min-width:0\\0){.t-menuburger_fourth:not(.t-menuburger-opened):hover span:nth-child(1){transform:translateY(1px);}.t-menuburger_fourth:not(.t-menuburger-opened):hover span:nth-child(4){transform:translateY(-1px);}.t-menuburger_fourth.t-menuburger__big:not(.t-menuburger-opened):hover span:nth-child(1){transform:translateY(3px);}.t-menuburger_fourth.t-menuburger__big:not(.t-menuburger-opened):hover span:nth-child(4){transform:translateY(-3px);}}.t-menuburger_fourth.t-menuburger-opened span:nth-child(1),.t-menuburger_fourth.t-menuburger-opened span:nth-child(4){top:4px;}.t-menuburger_fourth.t-menuburger-opened span:nth-child(2),.t-menuburger_fourth.t-menuburger-opened span:nth-child(3){opacity:1;}@keyframes t-menuburger-anim{0%{width:80%;left:20%;right:0;}50%{width:100%;left:0;right:0;}100%{width:80%;left:0;right:20%;}}@keyframes t-menuburger-anim2{0%{width:80%;left:0;}50%{width:100%;right:0;left:0;}100%{width:80%;left:20%;right:0;}}</style> </div> </div> <!-- t450 --> <div id=\"nav492947799marker\"></div> <div class=\"t450__overlay\"> <div class=\"t450__overlay_bg\"\nstyle=\" \"> </div> </div> <div id=\"nav492947799\" class=\"t450 \" data-tooltip-hook=\"#menuopen\"\nstyle=\"max-width: 260px;\"> <button type=\"button\"\nclass=\"t450__close-button t450__close t450_opened \"\naria-label=\"Close menu\"> <div class=\"t450__close_icon\" style=\"color:#ffffff;\"> <span></span> <span></span> <span></span> <span></span> </div> </button> <div class=\"t450__container t-align_left\"> <div class=\"t450__top\"> <nav class=\"t450__menu\"> <ul role=\"list\" class=\"t450__list t-menu__list\"> <li class=\"t450__list_item\"> <a class=\"t-menu__link-item t450__link-item_submenu\"\nhref=\"\"\naria-expanded=\"false\" role=\"button\" data-menu-submenu-hook=\"link_sub1_492947799\" data-menu-item-number=\"1\">\nГлавная\n</a> <div class=\"t-menusub\" data-submenu-hook=\"link_sub1_492947799\" data-submenu-margin=\"15px\" data-add-submenu-arrow=\"\"> <div class=\"t-menusub__menu\"> <div class=\"t-menusub__content\"> <ul role=\"list\" class=\"t-menusub__list\"> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/\" data-menu-item-number=\"1\">Главная</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/history\" data-menu-item-number=\"1\">История храма</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/newmartyrs\" data-menu-item-number=\"1\">Новомученики</a> </li> </ul> </div> </div> </div> </li> <li class=\"t450__list_item\"> <a class=\"t-menu__link-item \"\nhref=\"/clergy\" data-menu-submenu-hook=\"\" data-menu-item-number=\"2\">\nДуховенство\n</a> </li> <li class=\"t450__list_item\"> <a class=\"t-menu__link-item \"\nhref=\"/schedule\" data-menu-submenu-hook=\"\" data-menu-item-number=\"3\">\nРасписание Богослужений\n</a> </li> <li class=\"t450__list_item\"> <a class=\"t-menu__link-item t450__link-item_submenu\"\nhref=\"\"\naria-expanded=\"false\" role=\"button\" data-menu-submenu-hook=\"link_sub4_492947799\" data-menu-item-number=\"4\">\nПолезная информация\n</a> <div class=\"t-menusub\" data-submenu-hook=\"link_sub4_492947799\" data-submenu-margin=\"15px\" data-add-submenu-arrow=\"\"> <div class=\"t-menusub__menu\"> <div class=\"t-menusub__content\"> <ul role=\"list\" class=\"t-menusub__list\"> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/information\" data-menu-item-number=\"4\">Полезная информация</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/participle\" data-menu-item-number=\"4\">Таинство Причастия</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/christening\" data-menu-item-number=\"4\">Таинство Крещения</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/confession\" data-menu-item-number=\"4\">Таинство Покаяния</a> </li> </ul> </div> </div> </div> </li> <li class=\"t450__list_item\"> <a class=\"t-menu__link-item t450__link-item_submenu\"\nhref=\"\"\naria-expanded=\"false\" role=\"button\" data-menu-submenu-hook=\"link_sub5_492947799\" data-menu-item-number=\"5\">\nВоскресная школа\n</a> <div class=\"t-menusub\" data-submenu-hook=\"link_sub5_492947799\" data-submenu-margin=\"15px\" data-add-submenu-arrow=\"\"> <div class=\"t-menusub__menu\"> <div class=\"t-menusub__content\"> <ul role=\"list\" class=\"t-menusub__list\"> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/school\" data-menu-item-number=\"5\">Воскресная школа</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/artistic\" data-menu-item-number=\"5\">Художественное отделение</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/ski\" data-menu-item-number=\"5\">Лыжная секция</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/musical\" data-menu-item-number=\"5\">Музыкальное отдление</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/fishing\" data-menu-item-number=\"5\">Рыболовная секция</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/preschool\" data-menu-item-number=\"5\">Дошкольное образование</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/additional\" data-menu-item-number=\"5\">Дополнительное образование</a> </li> <li class=\"t-menusub__list-item t-name t-name_xs\"> <a class=\"t-menusub__link-item t-name t-name_xs\"\nhref=\"/folkchoir\" data-menu-item-number=\"5\">Народный хор </a> </li> </ul> </div> </div> </div> </li> <li class=\"t450__list_item\"> <a class=\"t-menu__link-item \"\nhref=\"/courses\" data-menu-submenu-hook=\"\" data-menu-item-number=\"6\">\nБиблейско-Богословские курсы\n</a> </li> <li class=\"t450__list_item\"> <a class=\"t-menu__link-item \"\nhref=\"/philanthropist\" data-menu-submenu-hook=\"\" data-menu-item-number=\"7\">\nСтать благотворителем\n</a> </li> <li class=\"t450__list_item\"> <a class=\"t-menu__link-item \"\nhref=\"/contacts\" data-menu-submenu-hook=\"\" data-menu-item-number=\"8\">\nКонтакты\n</a> </li> </ul> </nav> </div> <div class=\"t450__rightside\"> <div class=\"t450__rightcontainer\"> <div class=\"t450__right_social_links\"> <div class=\"t-sociallinks\"> <ul role=\"list\" class=\"t-sociallinks__wrapper\" aria-label=\"Соц. сети\"> <!-- new soclinks --><li class=\"t-sociallinks__item t-sociallinks__item_telegram\"><a href=\"https://t.me/+D0M97VeamSE4Zjgy\" target=\"_blank\" rel=\"nofollow\" aria-label=\"telegram\" style=\"width: 30px; height: 30px;\"><svg class=\"t-sociallinks__svg\" role=\"presentation\" width=30px height=30px viewBox=\"0 0 100 100\" fill=\"none\"\nxmlns=\"http://www.w3.org/2000/svg\"> <path fill-rule=\"evenodd\" clip-rule=\"evenodd\"\nd=\"M50 100c27.614 0 50-22.386 50-50S77.614 0 50 0 0 22.386 0 50s22.386 50 50 50Zm21.977-68.056c.386-4.38-4.24-2.576-4.24-2.576-3.415 1.414-6.937 2.85-10.497 4.302-11.04 4.503-22.444 9.155-32.159 13.734-5.268 1.932-2.184 3.864-2.184 3.864l8.351 2.577c3.855 1.16 5.91-.129 5.91-.129l17.988-12.238c6.424-4.38 4.882-.773 3.34.773l-13.49 12.882c-2.056 1.804-1.028 3.35-.129 4.123 2.55 2.249 8.82 6.364 11.557 8.16.712.467 1.185.778 1.292.858.642.515 4.111 2.834 6.424 2.319 2.313-.516 2.57-3.479 2.57-3.479l3.083-20.226c.462-3.511.993-6.886 1.417-9.582.4-2.546.705-4.485.767-5.362Z\"\nfill=\"#ffffff\" /> </svg></a></li>&nbsp;<li class=\"t-sociallinks__item t-sociallinks__item_vk\"><a href=\"https://vk.com/hramelisaveta\" target=\"_blank\" rel=\"nofollow\" aria-label=\"vk\" style=\"width: 30px; height: 30px;\"><svg class=\"t-sociallinks__svg\" role=\"presentation\" width=30px height=30px viewBox=\"0 0 100 100\" fill=\"none\"\nxmlns=\"http://www.w3.org/2000/svg\"> <path fill-rule=\"evenodd\" clip-rule=\"evenodd\"\nd=\"M50 100c27.614 0 50-22.386 50-50S77.614 0 50 0 0 22.386 0 50s22.386 50 50 50ZM25 34c.406 19.488 10.15 31.2 27.233 31.2h.968V54.05c6.278.625 11.024 5.216 12.93 11.15H75c-2.436-8.87-8.838-13.773-12.836-15.647C66.162 47.242 71.783 41.62 73.126 34h-8.058c-1.749 6.184-6.932 11.805-11.867 12.336V34h-8.057v21.611C40.147 54.362 33.838 48.304 33.556 34H25Z\"\nfill=\"#ffffff\" /> </svg></a></li><!-- /new soclinks --> </ul> </div> </div> </div> </div> </div> </div> <script>t_onReady(function() {var rec=document.querySelector('#rec492947799');if(!rec) return;rec.setAttribute('data-animationappear','off');rec.style.opacity=1;t_onFuncLoad('t450_initMenu',function() {t450_initMenu('492947799');});t_onFuncLoad('t_menuWidgets__init',function() {t_menuWidgets__init('492947799');});});</script> <style>#rec492947799 .t-menu__link-item{}@supports (overflow:-webkit-marquee) and (justify-content:inherit){#rec492947799 .t-menu__link-item,#rec492947799 .t-menu__link-item.t-active{opacity:1 !important;}}</style> <style>#rec492947799{--menusub-text-color:#ffffff;--menusub-bg:#fff;--menusub-shadow:none;--menusub-radius:0;--menusub-border:none;--menusub-width:150px;--menusub-transition:0.3s ease-in-out;}@media screen and (max-width:980px){#rec492947799 .t-menusub__menu .t-menusub__link-item,#rec492947799 .t-menusub__menu .t-menusub__innermenu-link{color:var(--menusub-text-color) !important;}#rec492947799 .t-menusub__menu .t-menusub__link-item.t-active{color:var(--menusub-text-color) !important;}#rec492947799 .t-menusub__menu .t-menusub__list-item:has(.t-active){background-color:var(--menusub-hover-bg);}}@media screen and (max-width:980px){#rec492947799 .t-menusub__menu-wrapper{background-color:var(--menusub-bg) !important;border-radius:var(--menusub-radius) !important;border:var(--menusub-border) !important;box-shadow:var(--menusub-shadow) !important;overflow:auto;max-width:var(--mobile-max-width);}#rec492947799 .t-menusub__menu-wrapper .t-menusub__content{background-color:transparent !important;border:none !important;box-shadow:none !important;border-radius:0 !important;margin-top:0 !important;}}</style> <script>t_onReady(function() {setTimeout(function(){t_onFuncLoad('t_menusub_init',function() {t_menusub_init('492947799');});},500);});</script> <style>#rec492947799 a.t-menu__link-item{font-size:24px;color:#ffffff;}</style> <style>#rec492947799 .t450__right_langs_lang a{color:#ffffff;}</style> </div> </header> <!--/header--> <div id=\"rec492632588\" class=\"r t-rec t-rec_pt_150 t-rec_pb_0\" style=\"padding-top:150px;padding-bottom:0px; \" data-record-type=\"33\"> <!-- T017 --> <div class=\"t017\"> <div class=\"t-container t-align_center\"> <div class=\"t-col t-col_10 t-prefix_1\"> <div class=\"t017__uptitle t-uptitle t-uptitle_md\" field=\"subtitle\"><span style=\"font-family: Georgia;\">Елисаветинский храм Одинцовской епархии</span></div> <div class=\"t017__title t-title t-title_xxs\" field=\"title\"><span style=\"font-family: Georgia;\">Расписание Богослужений</span></div> </div> </div> </div> </div> <div id=\"rec493057783\" class=\"r t-rec t-rec_pt_0 t-rec_pb_0\" style=\"padding-top:0px;padding-bottom:0px; \" data-record-type=\"3\"> <!-- T107 --> <div class=\"t107\"> <div class=\"t-align_center\" itemscope itemtype=\"http://schema.org/ImageObject\"> <meta itemprop=\"image\" content=\"IMAGE_1\"> <img class=\"t-img t-width t107__width t-width_10\"\nsrc=\"EMPTY_1\" data-original=\"IMAGE_1\"\nimgfield=\"img\" data-zoomable=\"yes\" data-img-zoom-url=\"IMAGE_1\" alt=\"\"> </div> </div> </div> <div id=\"rec493057870\" class=\"r t-rec t-rec_pt_0 t-rec_pb_0\" style=\"padding-top:0px;padding-bottom:0px; \" data-record-type=\"3\"> <!-- T107 --> <div class=\"t107\"> <div class=\"t-align_center\" itemscope itemtype=\"http://schema.org/ImageObject\"> <meta itemprop=\"image\" content=\"IMAGE_2\"> <img class=\"t-img t-width t107__width t-width_10\"\nsrc=\"EMPTY_2\" data-original=\"IMAGE_2\"\nimgfield=\"img\" data-zoomable=\"yes\" data-img-zoom-url=\"IMAGE_2\" alt=\"\"> </div> </div> </div> <div id=\"rec493057971\" class=\"r t-rec t-rec_pt_0 t-rec_pb_0\" style=\"padding-top:0px;padding-bottom:0px; \" data-record-type=\"3\"> <!-- T107 --> <div class=\"t107\"> <div class=\"t-align_center\" itemscope itemtype=\"http://schema.org/ImageObject\"> <meta itemprop=\"image\" content=\"IMAGE_3\"> <img class=\"t-img t-width t107__width t-width_10\"\nsrc=\"EMPTY_3\" data-original=\"IMAGE_3\"\nimgfield=\"img\" data-zoomable=\"yes\" data-img-zoom-url=\"IMAGE_3\" alt=\"\"> </div> </div> </div> <!--footer--> <footer id=\"t-footer\" class=\"t-records\" data-hook=\"blocks-collection-content-node\" data-tilda-project-id=\"5957836\" data-tilda-page-id=\"30457321\" data-tilda-page-alias=\"fyter\" data-tilda-formskey=\"6063156a0c93b67ed1b1a3c135957836\" data-tilda-lazy=\"yes\" data-tilda-root-zone=\"com\" data-tilda-project-country=\"RU\"> <div id=\"rec492949284\" class=\"r t-rec\" style=\"background-color:#111111; \" data-animationappear=\"off\" data-record-type=\"345\" data-bg-color=\"#111111\"> <!-- T345 --> <div class=\"t345\" id=\"footer_492949284\"> <div class=\"t345-wrapper\" style=\"border-top: 1px solid;border-top-color: #292929;\"> <div class=\"t345-container t-container\"> <div class=\"t345-col t-col t-col_12\"> <div\nclass=\"t345-content\"\nstyle=\"height:120px\"> <div class=\"t345-text__wrapper\"> <div class=\"t345-text t-descr t-descr_xxs\">Елисаветинский храм Одинцовская и Красногорска епархия<br />г. Красногорск, мкр. Опалиха, ул. Пушкина, д. 2,<br /><a href=\"https://елисаветинский-храм.рф\" target=\"_blank\" rel=\"noreferrer noopener\" style=\"font-family: Georgia;\"><strong><u>https://елисаветинский-храм.рф</u></strong></a> , <a href=\"tel:+74955649773\"><u>8(495)564-97-73</u></a></div> </div> <div class=\"t345-socials\"> <div class=\"t-sociallinks\"> <ul role=\"list\" class=\"t-sociallinks__wrapper\" aria-label=\"Соц. сети\"> <!-- new soclinks --><li class=\"t-sociallinks__item t-sociallinks__item_vk\"><a href=\"https://vk.com/hramelisaveta\" target=\"_blank\" rel=\"nofollow\" aria-label=\"vk\" style=\"width: 30px; height: 30px;\"><svg class=\"t-sociallinks__svg\" role=\"presentation\" width=30px height=30px viewBox=\"0 0 100 100\" fill=\"none\"\nxmlns=\"http://www.w3.org/2000/svg\"> <path fill-rule=\"evenodd\" clip-rule=\"evenodd\"\nd=\"M50 100c27.614 0 50-22.386 50-50S77.614 0 50 0 0 22.386 0 50s22.386 50 50 50ZM25 34c.406 19.488 10.15 31.2 27.233 31.2h.968V54.05c6.278.625 11.024 5.216 12.93 11.15H75c-2.436-8.87-8.838-13.773-12.836-15.647C66.162 47.242 71.783 41.62 73.126 34h-8.058c-1.749 6.184-6.932 11.805-11.867 12.336V34h-8.057v21.611C40.147 54.362 33.838 48.304 33.556 34H25Z\"\nfill=\"#ffffff\" /> </svg></a></li><!-- /new soclinks --> </ul> </div> </div> </div> </div> </div> </div> </div> <style>#rec492949284 .t345-text{color:#ffffff;}</style> <script>function checkSize_492949284(){var rec=document.querySelector('#footer_492949284');if(!rec) return;var textContentEl=rec.querySelector('.t345-text');var iconsContentEl=rec.querySelector('.t345-socials');if(!textContentEl||!iconsContentEl) return;var contentWidth=textContentEl.offsetWidth + iconsContentEl.offsetWidth;var container=rec.querySelector('.t345-container');var containerWidth=container.offsetWidth - 30;if(contentWidth>containerWidth&&containerWidth>0) {container.classList.add('t345-socials_block');}\nelse {container.classList.remove('t345-socials_block');}}\nt_onReady(function() {t_onFuncLoad('checkSize_492949284',function(){checkSize_492949284();window.addEventListener('resize',t_throttle(function(){checkSize_492949284();}));if(typeof jQuery!=='undefined') {\$('#rec492949284').bind('displayChanged',function() {checkSize_492949284();});} else {var rec=document.querySelector('#rec492949284');if(!rec) return;rec.addEventListener('displayChanged',function() {checkSize_492949284();});}});});</script> </div> </footer> <!--/footer--> </div> <!--/allrecords--> <!-- Tilda copyright. Don't remove this line --><div class=\"t-tildalabel \" id=\"tildacopy\" data-tilda-sign=\"5957836#30438951\"><a href=\"https://tilda.cc/\" class=\"t-tildalabel__link\"><div class=\"t-tildalabel__wrapper\"><div class=\"t-tildalabel__txtleft\">Made on </div><div class=\"t-tildalabel__wrapimg\"><img src=\"https://static.tildacdn.com/img/tildacopy.png\" class=\"t-tildalabel__img\" fetchpriority=\"low\" alt=\"\"></div><div class=\"t-tildalabel__txtright\">Tilda</div></div></a></div> <!-- Stat --> <script type=\"text/javascript\">if(!window.mainTracker) {window.mainTracker='tilda';}\nsetTimeout(function(){(function(d,w,k,o,g) {var n=d.getElementsByTagName(o)[0],s=d.createElement(o),f=function(){n.parentNode.insertBefore(s,n);};s.type=\"text/javascript\";s.async=true;s.key=k;s.id=\"tildastatscript\";s.src=g;if(w.opera==\"[object Opera]\") {d.addEventListener(\"DOMContentLoaded\",f,false);} else {f();}})(document,window,'91860da6baa5fc9d021e0b902fbdbc2b','script','https://static.tildacdn.com/js/tilda-stat-1.0.min.js');},2000);</script> </body> </html>"
}
