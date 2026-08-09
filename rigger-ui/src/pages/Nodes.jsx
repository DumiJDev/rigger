import React from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import PageHeader from "../components/shared/PageHeader";
import StatusBadge from "../components/shared/StatusBadge";
import DataTable from "../components/shared/DataTable";

export default function Nodes() {
  const { data: nodes = [], isLoading } = useQuery({
    queryKey: ["nodes"], queryFn: api.nodes, refetchInterval: 15_000,
  });

  const columns = [
    { key: "name",    label: "Name" },
    { key: "ip",      label: "IP" },
    { key: "role",    label: "Role" },
    { key: "status",  label: "Status",  render: r => <StatusBadge status={r.status} /> },
    { key: "primary", label: "Primary", render: r => r.primary ? "✓" : "" },
    { key: "swarmNodeId", label: "Swarm ID", render: r => r.swarmNodeId?.slice(0,12) ?? "—" },
  ];

  return (
    <div>
      <PageHeader title="Nodes" subtitle="Cluster nodes and their current status" />
      {isLoading ? <p className="text-gray-400">Loading…</p> : <DataTable columns={columns} rows={nodes} />}
    </div>
  );
}
