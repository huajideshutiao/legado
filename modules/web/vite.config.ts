import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import Icons from "unplugin-icons/vite";
import IconsResolver from "unplugin-icons/resolver";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  return {
    plugins: [
      vue(),
      AutoImport({
        imports: ["vue", "vue-router", "pinia"],
        include: [/\.[tj]sx?$/, /\.vue$/, /\.vue\?vue/],
        dirs: ["src/components", "src/store", "*.d.ts"],
        eslintrc: {
          //enabled: true,
        },
        resolvers: [
          ElementPlusResolver(),
          IconsResolver({
            prefix: "Icon",
          }),
        ],
        dts: "./src/auto-imports.d.ts",
      }),
      Components({
        resolvers: [
          ElementPlusResolver(),
          IconsResolver({
            enabledCollections: ["ep"],
          }),
        ],
        dts: "./src/components.d.ts",
      }),
      Icons({
        autoInstall: true,
      }),
    ],
    base: mode === "development" ? "/" : "./",
    server: {
      port: 8080,
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
      rollupOptions: {
        output: {
          manualChunks: (id) => {
            if (id.includes("node_modules")) {
              // 更细粒度的代码分割
              if (id.includes("element-plus")) {
                return "vendor-element";
              }
              if (id.includes("vue")) {
                return "vendor-vue";
              }
              return "vendor-other";
            }
          },
        },
      },
      // 构建优化
      assetsDir: 'assets',
      minify: 'terser',
      terserOptions: {
        compress: {
          drop_console: true,
          drop_debugger: true,
        },
      },
      // 分析构建产物
      reportCompressedSize: true,
      emptyOutDir: true,
    },
    css: {
      preprocessorOptions: {
        scss: {
          api: 'modern-compiler', // or 'modern'
        },
      },
    },
  }
});