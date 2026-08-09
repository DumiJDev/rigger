import React, { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import PageHeader from "../components/shared/PageHeader";
import StatusBadge from "../components/shared/StatusBadge";
import DataTable from "../components/shared/DataTable";

export default function AuditLog() {
  const [ns] = useState("production");
  const [page, setPage] = useState(0);
  const { data, isLoading } = useQuery({
    queryKey: ["audit", ns, page], queryFn: () => api.audit(ns, page), keepPreviousData: true,
  });
  const items = data?.content ?? [];
  const columns = [
    { key: "timestamp",    label: "Time",     render: r => new Date(r.timestamp).toLocaleTimeString() },
    { key: "identityName", label: "Identity" },
    { key: "action",       label: "Action" },
    { key: "resourceKind", label: "Kind" },
    { key: "resourceName", label: "Resource" },
    { key: "result",       label: "Result", render: r => <StatusBadge status={r.result} /> },
  ];
  return (
    <div>
      <PageHeader title="Audit Log" subtitle="Immutable record of all cluster operations" />
      {isLoading ? <p className="text-gray-400">Loading…</p> :
        <>
          <DataTable columns={columns} rows={items} emptyMsg="No audit entries" />
          <div className="flex gap-2 mt-4 justify-end">
            <button onClick={() => setPage(p => Math.max(0, p-1))} disabled={page === 0}
              className="px-3 py-1.5 text-sm border rounded disabled:opacity-40">← Prev</button>
            <span className="px-3 py-1.5 text-sm">Page {page + 1}</span>
            <button onClick={() => setPage(p => p+1)} disabled={items.length < 50}
              className="px-3 py-1.5 text-sm border rounded disabled:opacity-40">Next →</button>
          </div>
        </>
      }
    </div>
  );
}
