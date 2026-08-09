import React, { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import PageHeader from "../components/shared/PageHeader";
import DataTable from "../components/shared/DataTable";
import { Lock } from "lucide-react";

export default function Secrets() {
  const [ns] = useState("production");
  const { data: items = [], isLoading } = useQuery({
    queryKey: ["secrets", ns], queryFn: () => api.secrets(ns), refetchInterval: 60_000,
  });
  const columns = [
    { key: "name",      label: "Name" },
    { key: "namespace", label: "Namespace" },
    { key: "values",    label: "Values",   render: () =>
        <span className="flex items-center gap-1 text-gray-400 text-xs"><Lock size={12} /> redacted</span> },
    { key: "appliedBy", label: "Applied By" },
  ];
  return (
    <div>
      <PageHeader title="Secrets" subtitle="Values are never exposed in the UI" />
      {isLoading ? <p className="text-gray-400">Loading…</p> : <DataTable columns={columns} rows={items} />}
    </div>
  );
}
