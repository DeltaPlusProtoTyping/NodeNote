// Readies the browser app for the server jar (run by `gradlew :server:installDist -PwithWeb`):
//  - brotli and gzip copies of every big file, which the server sends as they are
//    (brotli makes the first visit about a quarter smaller than on-the-fly gzip);
//  - preload hints in index.html, so a phone starts downloading the two .wasm files at
//    once instead of after app.js has loaded and run.
//
//   node tools/prepare-web.mjs <dist dir> <out dir>
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

const [src, out] = process.argv.slice(2);
if (!src || !out) {
    console.error("usage: node prepare-web.mjs <dist dir> <out dir>");
    process.exit(2);
}
fs.rmSync(out, { recursive: true, force: true });
// Source maps only help a debugger; they'd just make the jar bigger.
fs.cpSync(src, out, { recursive: true, filter: (f) => !f.endsWith(".map") });

// The .wasm files app.js loads (webpack names them by content hash).
const appJs = fs.readFileSync(path.join(out, "app.js"), "utf8");
const wasm = [...new Set(appJs.match(/[0-9a-f]{16,}\.wasm/g) ?? [])].filter((f) => fs.existsSync(path.join(out, f)));

// Added by a script rather than as plain <link> tags: if index.html is about to redirect to
// add the trailing slash, relative links would point at the wrong place, so skip them then.
// crossorigin matches how the loaders fetch them, so the browser reuses these downloads.
const indexPath = path.join(out, "index.html");
const html = fs.readFileSync(indexPath, "utf8");
if (wasm.length && !html.includes("data-preload")) {
    const preload = `    <script data-preload>
        if (location.pathname.endsWith('/') || /\\.[a-z0-9]+$/i.test(location.pathname)) {
            ${JSON.stringify(wasm)}.forEach(function (f) {
                var l = document.createElement('link');
                l.rel = 'preload'; l.href = f; l.as = 'fetch'; l.type = 'application/wasm'; l.crossOrigin = 'anonymous';
                document.head.appendChild(l);
            });
        }
    </script>
`;
    fs.writeFileSync(indexPath, html.replace("</head>", preload + "</head>"));
}

// Precompressed copies next to each file worth compressing.
const compressible = /\.(wasm|js|mjs|html|css|json|txt|svg)$/i;
let raw = 0;
let brotli = 0;
function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            walk(p);
            continue;
        }
        if (!compressible.test(entry.name)) continue;
        const data = fs.readFileSync(p);
        if (data.length < 1024) continue;
        const br = zlib.brotliCompressSync(data, {
            params: {
                [zlib.constants.BROTLI_PARAM_QUALITY]: 11,
                [zlib.constants.BROTLI_PARAM_SIZE_HINT]: data.length,
            },
        });
        fs.writeFileSync(p + ".br", br);
        fs.writeFileSync(p + ".gz", zlib.gzipSync(data, { level: 9 }));
        raw += data.length;
        brotli += br.length;
    }
}
walk(out);
console.log(`prepare-web: ${wasm.length} wasm preloads; ${(raw / 1e6).toFixed(1)} MB -> ${(brotli / 1e6).toFixed(1)} MB with brotli`);
