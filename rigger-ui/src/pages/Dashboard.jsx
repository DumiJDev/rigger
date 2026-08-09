import React from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { Server, Boxes, Activity, AlertTriangle } from "lucide-react";
import PageHeader from "../components/shared/PageHeader";

function StatCard({ icon: Icon, label, value, color = "teal" }) {
  const colors = { teal: "text-[#0F6E56]", red: "text-[#A32D2D]", amber: "text-[#854F0B]", navy: "text-[#1B2A4A]" };
  return (
    <div className="bg-white rounded-lg border border-gray-200 p-5 shadow-sm flex items-center gap-4">
      <div className={`${colors[color]} bg-gray-50 p-3 rounded-lg`}><Icon size={22} /></div>
      <div>
        <p className="text-xs text-gray-500 uppercase tracking-wide">{label}</p>
        <p className="text-2xl font-bold text-gray-900">{value ?? "…"}</p>
      </div>
    </div>
  );
}

export default function Dashboard() {
  const { data: cluster } = useQuery({ queryKey: ["cluster"], queryFn: api.clusterStatus, refetchInterval: 15_000 });
  const { data: nodes }   = useQuery({ queryKey: ["nodes"],   queryFn: api.nodes,         refetchInterval: 15_000 });
  const ns = "production";
  const { data: deploys } = useQuery({ queryKey: ["deployments", ns], queryFn: () => api.deployments(ns), refetchInterval: 15_000 });

  const active  = nodes?.filter(n => n.status === "ACTIVE").length ?? 0;
  const total   = nodes?.length ?? 0;
  const offline = nodes?.filter(n => n.status === "OFFLINE").length ?? 0;

  return (
    <div>
      <PageHeader title="Dashboard" subtitle="Cluster overview at a glance" />

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        <StatCard icon={Server}       label="Active Nodes"   value={`${active}/${total}`} color="teal" />
        <StatCard icon={Boxes}        label="Deployments"    value={deploys?.length ?? 0}  color="navy" />
        <StatCard icon={Activity}     label="Cluster Status" value={cluster?.status ?? "…"} color="teal" />
        <StatCard icon={AlertTriangle} label="Offline Nodes" value={offline}                color={offline > 0 ? "red" : "teal"} />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="bg-white rounded-lg border border-gray-200 shadow-sm">
          <div className="px-5 py-4 border-b border-gray-100">
            <h2 className="font-semibold text-gray-800">Nodes</h2>
          </div>
          <div className="divide-y divide-gray-50">
            {(nodes ?? []).map(n => (
              <div key={n.name} className="flex items-center justify-between px-5 py-3">
                <div>
                  <span className="font-medium text-sm text-gray-800">{n.name}</span>
                  <span className="ml-2 text-xs text-gray-400">{n.ip}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-gray-500 uppercase">{n.role}</span>
                  <span className={`px-2 py-0.5 rounded text-xs font-medium
                    ${n.status === "ACTIVE" ? "bg-green-100 text-green-800" : "bg-red-100 text-red-800"}`}>
                    {n.status}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="bg-white rounded-lg border border-gray-200 shadow-sm">
          <div className="px-5 py-4 border-b border-gray-100">
            <h2 className="font-semibold text-gray-800">Recent Deployments</h2>
          </div>
          <div className="divide-y divide-gray-50">
            {(deploys ?? []).slice(0, 6).map(d => (
              <div key={d.name} className="flex items-center justify-between px-5 py-3">
                <div>
                  <span className="font-medium text-sm text-gray-800">{d.name}</span>
                  <span className="ml-2 text-xs text-gray-400">{d.namespace}</span>
                </div>
                <span className="text-xs text-gray-500">{d.spec?.replicas ?? 0} replicas</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
