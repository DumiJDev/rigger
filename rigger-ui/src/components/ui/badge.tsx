import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset",
  {
    variants: {
      variant: {
        default:  "bg-gray-100 text-gray-700 ring-gray-200",
        success:  "bg-green-50 text-green-700 ring-green-200",
        warning:  "bg-yellow-50 text-yellow-700 ring-yellow-200",
        danger:   "bg-red-50 text-red-700 ring-red-200",
        blue:     "bg-blue-50 text-blue-700 ring-blue-200",
      },
    },
    defaultVariants: { variant: "default" },
  }
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

export function Badge({ className, variant, ...props }: BadgeProps) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />;
}
