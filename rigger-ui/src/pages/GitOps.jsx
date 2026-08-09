import React from "react";
import { useQuery } from "@tanstack/react-query";
import { GitBranch, CheckCircle, XCircle, Clock } from "lucide-react";
import PageHeader from "../components/shared/PageHeader";

export default function GitOps() {
  // GitOps state endpoint — to be wired in Phase 5
  const { data } = useQuery({
    queryKey: ["gitops"], queryFn: async () => {
      const r = await fetch("/api/v1/gitops/state");
      return r.ok ? r.json() : null;
    },
    retry: false,
  });

  return (
    <div>
      <PageHeader title="GitOps" subtitle="Continuous delivery from Git" />
      <div className="bg-white rounded-lg border border-gray-200 shadow-sm p-6">
        {data ? (
          <div className="space-y-4">
            <div className="flex items-center gap-3">
              <GitBranch size={20} className="text-[#0F6E56]" />
              <div>
                <p className="font-medium text-gray-800">{data.repositoryUrl}</p>
                <p className="text-xs text-gray-500">Branch: {data.branch ?? "main"}</p>
              </div>
              {data.result === "SUCCESS"
                ? <CheckCircle size={18} className="ml-auto text-green-500" />
                : <XCircle    size={18} className="ml-auto text-red-500" />
              }
            </div>
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div className="bg-gray-50 rounded p-3">
                <p className="text-xs text-gray-500 mb-1">Last Commit</p>
                <code className="text-xs">{data.lastAppliedCommit?.slice(0,12)}</code>
              </div>
              <div className="bg-gray-50 rounded p-3">
                <p className="text-xs text-gray-500 mb-1">Last Sync</p>
                <p className="text-xs">{new Date(data.lastAppliedAt).toLocaleString()}</p>
              </div>
            </div>
          </div>
        ) : (
          <div className="text-center py-12 text-gray-400">
            <Clock size={40} className="mx-auto mb-3 opacity-40" />
            <p className="font-medium">GitOps not configured</p>
            <p className="text-sm mt-1">Set <code className="bg-gray-100 px-1 rounded">rigger.gitops.enabled=true</code> in application.yaml</p>
          </div>
        )}
      </div>
    </div>
  );
}
