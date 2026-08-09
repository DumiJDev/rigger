import React from "react";

const colours = {
  ACTIVE:       "bg-green-100 text-green-800",
  active:       "bg-green-100 text-green-800",
  running:      "bg-green-100 text-green-800",
  healthy:      "bg-green-100 text-green-800",
  SUCCESS:      "bg-green-100 text-green-800",
  OFFLINE:      "bg-red-100 text-red-800",
  failed:       "bg-red-100 text-red-800",
  DENIED:       "bg-red-100 text-red-800",
  ERROR:        "bg-red-100 text-red-800",
  DRAINING:     "bg-yellow-100 text-yellow-800",
  PROVISIONING: "bg-blue-100 text-blue-800",
  PENDING:      "bg-gray-100 text-gray-600",
};

export default function StatusBadge({ status }) {
  const cls = colours[status] ?? "bg-gray-100 text-gray-600";
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${cls}`}>
      {status}
    </span>
  );
}
