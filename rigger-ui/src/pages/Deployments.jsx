import React, { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import PageHeader from "../components/shared/PageHeader";
import DataTable from "../components/shared/DataTable";
import { BarChart2, Trash2 } from "lucide-react";

export default function Deployments() {
  const [ns] = useState("production");
  const qc   = useQueryClient();
  const { data: items = [], isLoading } = useQuery({
    queryKey: ["deployments", ns], queryFn: () => api.deployments(ns), refetchInterval: 15_000,
  });

  const scaleMut = useMutation({
    mutationFn: ({ name, replicas }) => api.scale(ns, name, replicas),
    onSuccess: () => qc.invalidateQueries(["deployments", ns]),
  });

  const deleteMut = useMutation({
    mutationFn: (name) => api.deleteResource(ns, "deployments", name),
    onSuccess: () => qc.invalidateQueries(["deployments", ns]),
  });

  const columns = [
    { key: "name",      label: "Name" },
    { key: "namespace", label: "Namespace" },
    { key: "replicas",  label: "Replicas", render: r => r.spec?.replicas ?? "—" },
    { key: "image",     label: "Image",    render: r => <code className="text-xs bg-gray-100 px-1 rounded">{r.spec?.image ?? "—"}</code> },
    { key: "appliedBy", label: "Applied By" },
    { key: "actions",   label: "",
      render: r => (
        <div className="flex gap-2">
          <button onClick={() => { const n = parseInt(prompt("Replicas?", r.spec?.replicas ?? 1)); if (n >= 0) scaleMut.mutate({ name: r.name, replicas: n }); }}
            className="p-1 text-gray-400 hover:text-[#0F6E56]" title="Scale">
            <BarChart2 size={15} />
          </button>
          <button onClick={() => { if (confirm(`Delete ${r.name}?`)) deleteMut.mutate(r.name); }}
            className="p-1 text-gray-400 hover:text-[#A32D2D]" title="Delete">
            <Trash2 size={15} />
          </button>
        </div>
      )
    },
  ];

  return (
    <div>
      <PageHeader title="Deployments" subtitle={`Namespace: ${ns}`} />
      {isLoading ? <p className="text-gray-400">Loading…</p> : <DataTable columns={columns} rows={items} emptyMsg="No deployments" />}
    </div>
  );
}
