/** @type {import("tailwindcss").Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        rigger: {
          navy:  "#1B2A4A",
          teal:  "#0F6E56",
          amber: "#854F0B",
          red:   "#A32D2D",
        }
      },
      fontFamily: {
        sans: ["Inter", "system-ui", "sans-serif"],
      }
    }
  },
  plugins: [],
};
