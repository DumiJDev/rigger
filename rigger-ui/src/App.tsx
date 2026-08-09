import React from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import Layout from "./components/layout/Layout";
import Dashboard  from "./pages/Dashboard";
import Nodes      from "./pages/Nodes";
import Deployments from "./pages/Deployments";
import Services   from "./pages/Services";
import Secrets    from "./pages/Secrets";
import AuditLog   from "./pages/AuditLog";
import GitOps     from "./pages/GitOps";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard"   element={<Dashboard />} />
        <Route path="nodes"       element={<Nodes />} />
        <Route path="deployments" element={<Deployments />} />
        <Route path="services"    element={<Services />} />
        <Route path="secrets"     element={<Secrets />} />
        <Route path="audit"       element={<AuditLog />} />
        <Route path="gitops"      element={<GitOps />} />
      </Route>
    </Routes>
  );
}
