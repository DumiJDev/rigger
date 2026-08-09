import { useState } from "react";
import { Outlet, NavLink } from "react-router-dom";
import {
  LayoutDashboard, Server, Boxes, Network, Lock, BookOpen, GitBranch, Menu, X, Anchor
} from "lucide-react";

const nav = [
  { to: "/dashboard",   label: "Dashboard",   icon: LayoutDashboard },
  { to: "/nodes",       label: "Nodes",       icon: Server },
  { to: "/deployments", label: "Deployments", icon: Boxes },
  { to: "/services",    label: "Services",    icon: Network },
  { to: "/secrets",     label: "Secrets",     icon: Lock },
  { to: "/gitops",      label: "GitOps",      icon: GitBranch },
  { to: "/audit",       label: "Audit Log",   icon: BookOpen },
];

export default function Layout() {
  const [open, setOpen] = useState(true);
  return (
    <div className="flex h-screen overflow-hidden bg-gray-50">
      {/* Sidebar */}
      <aside className={`${open ? "w-56" : "w-14"} flex-shrink-0 bg-[#1B2A4A] text-white flex flex-col transition-all duration-200`}>
        <div className="flex items-center gap-2 p-4 border-b border-white/10">
          <Anchor size={22} className="text-[#0F6E56] flex-shrink-0" />
          {open && <span className="font-bold text-lg tracking-wide">Rigger</span>}
          <button onClick={() => setOpen(!open)} className="ml-auto text-white/60 hover:text-white">
            {open ? <X size={16} /> : <Menu size={16} />}
          </button>
        </div>
        <nav className="flex-1 py-4 space-y-0.5">
          {nav.map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to}
              className={({ isActive }) =>
                `flex items-center gap-3 px-4 py-2.5 text-sm transition-colors rounded-r-full mr-2
                 ${isActive ? "bg-[#0F6E56] text-white font-medium" : "text-white/70 hover:bg-white/10 hover:text-white"}`
              }>
              <Icon size={18} className="flex-shrink-0" />
              {open && <span>{label}</span>}
            </NavLink>
          ))}
        </nav>
        {open && (
          <div className="p-4 border-t border-white/10 text-xs text-white/40">
            v1.0.0 · rigger.io
          </div>
        )}
      </aside>

      {/* Main */}
      <main className="flex-1 overflow-auto">
        <div className="p-6 max-w-6xl mx-auto">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
