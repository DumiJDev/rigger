import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50",
  {
    variants: {
      variant: {
        default:   "bg-[#1B2A4A] text-white hover:bg-[#1B2A4A]/90",
        teal:      "bg-[#0F6E56] text-white hover:bg-[#0F6E56]/90",
        outline:   "border border-gray-200 bg-white hover:bg-gray-50 text-gray-700",
        ghost:     "hover:bg-gray-100 text-gray-700",
        danger:    "bg-[#A32D2D] text-white hover:bg-[#A32D2D]/90",
        secondary: "bg-gray-100 text-gray-800 hover:bg-gray-200",
      },
      size: {
        default: "h-9 px-4 py-2",
        sm:      "h-7 px-3 text-xs",
        lg:      "h-11 px-8",
        icon:    "h-9 w-9",
      },
    },
    defaultVariants: { variant: "default", size: "default" },
  }
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, ...props }, ref) => (
    <button className={cn(buttonVariants({ variant, size, className }))} ref={ref} {...props} />
  )
);
Button.displayName = "Button";
