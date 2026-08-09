import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../rigger-server/src/main/resources/static/ui",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/api":      { target: "https://localhost:7433", secure: false },
      "/actuator": { target: "https://localhost:7433", secure: false },
    },
  },
});
