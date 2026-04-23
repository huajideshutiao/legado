import { URL } from "node:url";
import fs from "node:fs";
import process from "node:process";

if (!process.env.GITHUB_ENV) {
  console.log("非Github WorkFlows环境，取消文件复制");
  process.exit();
}
const LEGADO_ASSETS_WEB_DIR = new URL(
  "../../../app/src/main/assets/web",
  import.meta.url,
);
const VUE_DIST_DIR = new URL("../dist", import.meta.url);

console.log("> copy dist to", LEGADO_ASSETS_WEB_DIR.pathname);

fs.copyFileSync(
  new URL("index.html", VUE_DIST_DIR),
  new URL("index.html", LEGADO_ASSETS_WEB_DIR),
);
fs.copyFileSync(
  new URL("favicon.ico", VUE_DIST_DIR),
  new URL("favicon.ico", LEGADO_ASSETS_WEB_DIR),
);

console.log("> copy success");
