import React, { useState } from 'react';
import { 
  LayoutDashboard, 
  Building2, 
  ArrowLeftRight, 
  Users, 
  FileText, 
  Settings,
  Bell,
  Search,
  Menu,
  X,
  ChevronDown,
  LogOut,
  User,
  HelpCircle,
  Shield,
  Wallet,
  UserCheck,
  Server,
  ChevronRight,
} from 'lucide-react';
import { cn } from '../../utils';

// Navigation Item Type
interface NavItem {
  icon: React.ReactNode;
  label: string;
  href: string;
  badge?: number;
  children?: NavItem[];
}

interface NavSection {
  title?: string;
  items: NavItem[];
}

// Navigation Structure
const navSections: NavSection[] = [
  {
    items: [
      { icon: <LayoutDashboard className="w-5 h-5" />, label: 'Dashboard', href: 'dashboard' },
    ]
  },
  {
    title: 'Account Management',
    items: [
      { icon: <Building2 className="w-5 h-5" />, label: 'Virtual Accounts', href: 'accounts' },
      { icon: <Users className="w-5 h-5" />, label: 'Beneficiaries', href: 'beneficiaries' },
      { icon: <ArrowLeftRight className="w-5 h-5" />, label: 'Transactions', href: 'transactions' },
    ]
  },
  {
    title: 'Programs',
    items: [
      { icon: <Shield className="w-5 h-5" />, label: 'Digital Escrow', href: 'escrow', badge: 3 },
      { icon: <Wallet className="w-5 h-5" />, label: 'Wallet Programs', href: 'wallet' },
      { icon: <UserCheck className="w-5 h-5" />, label: 'KYCC', href: 'kycc', badge: 2 },
    ]
  },
  {
    title: 'Reports',
    items: [
      { icon: <FileText className="w-5 h-5" />, label: 'Statements', href: 'statements' },
    ]
  },
  {
    title: 'Administration',
    items: [
      { icon: <Server className="w-5 h-5" />, label: 'Sync Monitor', href: 'sync-admin' },
      { icon: <Settings className="w-5 h-5" />, label: 'Settings', href: 'settings' },
    ]
  },
];

// Sidebar Component
interface SidebarProps {
  isOpen: boolean;
  currentPath: string;
  onNavigate: (path: string) => void;
  onClose: () => void;
}

const Sidebar: React.FC<SidebarProps> = ({ isOpen, currentPath, onNavigate, onClose }) => {
  return (
    <>
      {/* Mobile Overlay */}
      {isOpen && (
        <div 
          className="fixed inset-0 bg-black/50 backdrop-blur-sm z-40 lg:hidden"
          onClick={onClose}
        />
      )}
      
      {/* Sidebar */}
      <aside
        className={cn(
          'fixed top-0 left-0 h-full w-64 bg-primary-950 z-50',
          'transform transition-transform duration-300 ease-out',
          'lg:translate-x-0 lg:static',
          'flex flex-col',
          isOpen ? 'translate-x-0' : '-translate-x-full'
        )}
      >
        {/* Logo */}
        <div className="flex items-center justify-between h-16 px-6 border-b border-primary-800 flex-shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-accent-400 to-accent-600 rounded-lg flex items-center justify-center shadow-lg">
              <span className="text-white font-bold text-lg">V</span>
            </div>
            <div>
              <span className="text-white font-semibold text-lg">VAM Portal</span>
              <span className="block text-primary-400 text-caption">Enterprise</span>
            </div>
          </div>
          <button 
            onClick={onClose}
            className="lg:hidden text-primary-400 hover:text-white p-1"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Navigation */}
        <nav className="flex-1 overflow-y-auto py-4 scrollbar-thin">
          {navSections.map((section, sectionIndex) => (
            <div key={sectionIndex} className="mb-2">
              {section.title && (
                <p className="px-6 py-2 text-caption font-medium text-primary-500 uppercase tracking-wider">
                  {section.title}
                </p>
              )}
              <div className="space-y-0.5 px-3">
                {section.items.map((item) => (
                  <button
                    key={item.href}
                    onClick={() => {
                      onNavigate(item.href);
                      onClose();
                    }}
                    className={cn(
                      'w-full flex items-center gap-3 px-3 py-2.5 rounded-lg',
                      'text-body-sm transition-all duration-200',
                      currentPath === item.href
                        ? 'bg-primary-800/80 text-white shadow-inner'
                        : 'text-primary-300 hover:bg-primary-900/50 hover:text-white'
                    )}
                  >
                    <span className={cn(
                      'transition-colors',
                      currentPath === item.href ? 'text-accent-400' : ''
                    )}>
                      {item.icon}
                    </span>
                    <span className="flex-1 text-left">{item.label}</span>
                    {item.badge && (
                      <span className={cn(
                        'px-2 py-0.5 rounded-full text-caption font-medium',
                        currentPath === item.href
                          ? 'bg-accent-500 text-white'
                          : 'bg-primary-800 text-primary-300'
                      )}>
                        {item.badge}
                      </span>
                    )}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </nav>

        {/* Bottom Section */}
        <div className="flex-shrink-0 p-4 border-t border-primary-800">
          <button className="w-full flex items-center gap-3 px-3 py-2.5 text-primary-300 hover:text-white hover:bg-primary-900/50 rounded-lg transition-colors">
            <HelpCircle className="w-5 h-5" />
            <span className="text-body-sm">Help & Support</span>
          </button>
        </div>
      </aside>
    </>
  );
};

// Header Component
interface HeaderProps {
  onMenuClick: () => void;
}

const Header: React.FC<HeaderProps> = ({ onMenuClick }) => {
  const [showUserMenu, setShowUserMenu] = useState(false);
  const [showNotifications, setShowNotifications] = useState(false);

  const notifications = [
    { id: 1, title: 'Escrow Dispute Raised', message: 'Contract ESC-2024-001236 has a new dispute', time: '5 min ago', type: 'warning' },
    { id: 2, title: 'KYCC Pending', message: '2 new KYCC records awaiting verification', time: '1 hour ago', type: 'info' },
    { id: 3, title: 'Sync Completed', message: '156 operations synced to BaNCS', time: '2 hours ago', type: 'success' },
  ];

  return (
    <header className="sticky top-0 z-30 bg-white/95 backdrop-blur-sm border-b border-neutral-200">
      <div className="flex items-center justify-between h-16 px-4 lg:px-8">
        {/* Left */}
        <div className="flex items-center gap-4">
          <button
            onClick={onMenuClick}
            className="lg:hidden p-2 hover:bg-neutral-100 rounded-lg transition-colors"
          >
            <Menu className="w-5 h-5 text-neutral-600" />
          </button>
          
          {/* Search */}
          <div className="hidden md:flex items-center gap-2 px-4 py-2.5 bg-neutral-100 rounded-xl w-80 hover:bg-neutral-200/70 transition-colors group">
            <Search className="w-4 h-4 text-neutral-400 group-hover:text-neutral-500" />
            <input
              type="text"
              placeholder="Search accounts, transactions..."
              className="flex-1 bg-transparent text-body-sm outline-none placeholder:text-neutral-400"
            />
            <kbd className="text-caption text-neutral-400 bg-white px-1.5 py-0.5 rounded border border-neutral-200 shadow-sm">
              ⌘K
            </kbd>
          </div>
        </div>

        {/* Right */}
        <div className="flex items-center gap-2">
          {/* Notifications */}
          <div className="relative">
            <button
              onClick={() => setShowNotifications(!showNotifications)}
              className="relative p-2.5 hover:bg-neutral-100 rounded-xl transition-colors"
            >
              <Bell className="w-5 h-5 text-neutral-600" />
              <span className="absolute top-2 right-2 w-2 h-2 bg-error-500 rounded-full ring-2 ring-white" />
            </button>
            
            {showNotifications && (
              <>
                <div 
                  className="fixed inset-0 z-10"
                  onClick={() => setShowNotifications(false)}
                />
                <div className="absolute right-0 top-full mt-2 w-96 bg-white rounded-2xl shadow-strong border border-neutral-200 z-20 overflow-hidden">
                  <div className="p-4 border-b border-neutral-200 flex items-center justify-between">
                    <h3 className="text-heading-sm text-primary-900">Notifications</h3>
                    <button className="text-body-sm text-primary-600 hover:text-primary-700 font-medium">
                      Mark all read
                    </button>
                  </div>
                  <div className="max-h-96 overflow-y-auto">
                    {notifications.map((n) => (
                      <div key={n.id} className="p-4 hover:bg-neutral-50 cursor-pointer border-b border-neutral-100 last:border-0">
                        <div className="flex items-start gap-3">
                          <div className={cn(
                            'w-2 h-2 rounded-full mt-2 flex-shrink-0',
                            n.type === 'warning' && 'bg-warning-500',
                            n.type === 'info' && 'bg-info-500',
                            n.type === 'success' && 'bg-success-500'
                          )} />
                          <div>
                            <p className="text-body-sm font-medium text-primary-900">
                              {n.title}
                            </p>
                            <p className="text-body-sm text-neutral-500 mt-0.5">
                              {n.message}
                            </p>
                            <p className="text-caption text-neutral-400 mt-1">{n.time}</p>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                  <div className="p-3 border-t border-neutral-200 bg-neutral-50">
                    <button className="w-full text-body-sm text-primary-600 hover:text-primary-700 font-medium">
                      View All Notifications
                    </button>
                  </div>
                </div>
              </>
            )}
          </div>

          {/* User Menu */}
          <div className="relative">
            <button
              onClick={() => setShowUserMenu(!showUserMenu)}
              className="flex items-center gap-3 p-2 hover:bg-neutral-100 rounded-xl transition-colors"
            >
              <div className="w-9 h-9 bg-gradient-to-br from-primary-600 to-primary-800 rounded-xl flex items-center justify-center shadow-inner">
                <span className="text-white font-medium text-body-sm">JD</span>
              </div>
              <div className="hidden md:block text-left">
                <p className="text-body-sm font-medium text-primary-900">John Doe</p>
                <p className="text-caption text-neutral-500">Finance Manager</p>
              </div>
              <ChevronDown className="w-4 h-4 text-neutral-400 hidden md:block" />
            </button>

            {showUserMenu && (
              <>
                <div 
                  className="fixed inset-0 z-10"
                  onClick={() => setShowUserMenu(false)}
                />
                <div className="absolute right-0 top-full mt-2 w-64 bg-white rounded-2xl shadow-strong border border-neutral-200 py-1 z-20 overflow-hidden">
                  <div className="px-4 py-3 border-b border-neutral-200">
                    <p className="text-body-sm font-medium text-primary-900">John Doe</p>
                    <p className="text-caption text-neutral-500">john.doe@company.com</p>
                  </div>
                  <div className="py-1">
                    <button className="w-full flex items-center gap-3 px-4 py-2.5 text-body-sm text-primary-900 hover:bg-neutral-50">
                      <User className="w-4 h-4 text-neutral-500" />
                      Profile Settings
                    </button>
                    <button className="w-full flex items-center gap-3 px-4 py-2.5 text-body-sm text-primary-900 hover:bg-neutral-50">
                      <Settings className="w-4 h-4 text-neutral-500" />
                      Preferences
                    </button>
                  </div>
                  <hr className="border-neutral-200" />
                  <div className="py-1">
                    <button className="w-full flex items-center gap-3 px-4 py-2.5 text-body-sm text-error-600 hover:bg-error-50">
                      <LogOut className="w-4 h-4" />
                      Sign Out
                    </button>
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </header>
  );
};

// Main Layout Component
interface LayoutProps {
  children: React.ReactNode;
  currentPage: string;
  onNavigate: (page: string) => void;
}

export const Layout: React.FC<LayoutProps> = ({ children, currentPage, onNavigate }) => {
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <div className="min-h-screen bg-neutral-50">
      <Sidebar
        isOpen={sidebarOpen}
        currentPath={currentPage}
        onNavigate={onNavigate}
        onClose={() => setSidebarOpen(false)}
      />
      
      <div className="lg:ml-64 min-h-screen flex flex-col">
        <Header onMenuClick={() => setSidebarOpen(true)} />
        
        <main className="flex-1 p-4 lg:p-8">
          {children}
        </main>

        {/* Footer */}
        <footer className="border-t border-neutral-200 bg-white py-4 px-8">
          <div className="flex items-center justify-between text-caption text-neutral-500">
            <span>© 2024 VAM Portal. All rights reserved.</span>
            <span>Version 2.0.0 • BaNCS Integration</span>
          </div>
        </footer>
      </div>
    </div>
  );
};

export default Layout;
