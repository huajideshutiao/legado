import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { viteSingleFile } from "vite-plugin-singlefile";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  return {
    plugins: [
      vue(),
      AutoImport({
        imports: ["vue", "vue-router", "pinia"],
        include: [/\.[tj]sx?$/, /\.vue$/, /\.vue\?vue/],
        dirs: ["src/components", "src/store", "*.d.ts"],
        dts: "./src/auto-imports.d.ts",
      }),
      Components({
        dts: "./src/components.d.ts",
      }),
      viteSingleFile(),
    ],
    base: mode === "development" ? "/" : "./",
    server: {
      port: 8080,
      // 开发期代理: npm run dev 直连本机无头后端 (:headless:run, 默认端口 1122)。
      // 代理所有 legado WebApi 路径 (get*/save*/delete*/cover/image/WS/restoreBackup/addLocalBook等),
      // 不碰 vite 自身的 /src /@vite /node_modules。
      proxy: {
        // WebSocket 接口 (searchBook / bookSourceDebug): 后端 WebSocketServer 监听在 1123 (端口 + 1)
        "^/(searchBook|bookSourceDebug)": {
          target: "ws://127.0.0.1:1123",
          changeOrigin: true,
          ws: true,
        },
        // HTTP 接口: 后端 HttpServer 监听在 1122
        "^/(get|save|delete|cover|image|restoreBackup|addLocalBook|refreshToc|testReplaceRule)": {
          target: "http://127.0.0.1:1122",
          changeOrigin: true,
        },
      },
    },
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
        "@api": fileURLToPath(new URL("./src/api", import.meta.url)),
        "@utils": fileURLToPath(new URL("./src/utils/", import.meta.url)),
      },
    },
    esbuild: {
      drop: mode === "development" ? undefined : ["console", "debugger"],
    },
    build: {
      reportCompressedSize: true,
      emptyOutDir: true,
    },
    css: {
      preprocessorOptions: {
        scss: {
          api: 'modern-compiler',
        },
      },
    },
  }
});
