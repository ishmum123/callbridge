import { NavLink } from 'react-router-dom';
import { IconFlag, IconGrid, IconPhone, IconPulse, IconSettings, IconStore, IconUsers } from './icons';

export interface NavItem {
  to: string;
  label: string;
  icon: (p: React.SVGProps<SVGSVGElement>) => React.JSX.Element;
  badge?: number;
}

export const NAV: { section: string; items: NavItem[] }[] = [
  {
    section: 'Monitor',
    items: [
      { to: '/', label: 'Overview', icon: IconGrid },
      { to: '/calls', label: 'Calls', icon: IconPhone },
      { to: '/callers', label: 'Callers & profiles', icon: IconUsers },
    ],
  },
  {
    section: 'Domain',
    items: [{ to: '/insights', label: 'Health insights', icon: IconPulse }],
  },
  {
    section: 'Operate',
    items: [
      { to: '/follow-ups', label: 'Follow-ups', icon: IconFlag },
      { to: '/shops', label: 'Shops & devices', icon: IconStore },
    ],
  },
  {
    section: 'Admin',
    items: [{ to: '/admin', label: 'Administration', icon: IconSettings }],
  },
];

export function Sidebar({ open, onNavigate, pendingFollowUps }: { open: boolean; onNavigate: () => void; pendingFollowUps: number }) {
  return (
    <nav className={`sidebar${open ? ' open' : ''}`} aria-label="Primary">
      <div className="brand">
        <div className="brand-mark" aria-hidden>C</div>
        <div className="brand-name">
          CallBridge<small>admin</small>
        </div>
      </div>
      {NAV.map((group) => (
        <div key={group.section}>
          <div className="nav-section">{group.section}</div>
          <ul className="nav-list">
            {group.items.map((item) => (
              <li key={item.to}>
                <NavLink to={item.to} end={item.to === '/'} className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`} onClick={onNavigate}>
                  <item.icon />
                  <span>{item.label}</span>
                  {item.to === '/follow-ups' && pendingFollowUps > 0 && <span className="nav-badge" aria-label={`${pendingFollowUps} pending`}>{pendingFollowUps}</span>}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>
      ))}
      <div className="sidebar-footer">
        <div className="avatar" aria-hidden>A</div>
        <div className="who">
          Admin (demo)
          <small>CallBridge Pilot</small>
        </div>
      </div>
    </nav>
  );
}
