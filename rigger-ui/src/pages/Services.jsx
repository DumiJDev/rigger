import React, { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import PageHeader from "../components/shared/PageHeader";
import DataTable from "../components/shared/DataTable";

export default function Services() {
  const [ns] = useState("production");
  const { data: items = [], isLoading } = useQuery({
    queryKey: ["services", ns], queryFn: () => api.services(ns), refetchInterval: 30_000,
  });
  const columns = [
    { key: "name",      label: "Name" },
    { key: "namespace", label: "Namespace" },
    { key: "type",      label: "Type",   render: r => r.spec?.type ?? "—" },
    { key: "ports",     label: "Ports",  render: r =>
        (r.spec?.ports ?? []).map(p => `${p.port}:${p.targetPort}`).join(", ") || "—" },
    { key: "appliedBy", label: "Applied By" },
  ];
  return (
    <div>
      <PageHeader title="Services" subtitle={`Namespace: ${ns}`} />
      {isLoading ? <p className="text-gray-400">Loading…</p> : <DataTable columns={columns} rows={items} />}
    </div>
  );
}
