import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
  build: {
    // Built output goes directly into the Spring Boot static resources
    outDir: "../rigger-server/src/main/resources/static/ui",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/api":      { target: "https://localhost:7433", secure: false, changeOrigin: true },
      "/actuator": { target: "https://localhost:7433", secure: false, changeOrigin: true },
    },
  },
  base: "/ui/",
});
