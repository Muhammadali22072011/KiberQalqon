import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import Layout from './components/Layout';
import Landing from './pages/Landing';
import Login from './pages/Login';
import Overview from './pages/Overview';
import MapPage from './pages/MapPage';
import Feed from './pages/Feed';
import Threats from './pages/Threats';
import Devices from './pages/Devices';
import Groups from './pages/Groups';
import Users from './pages/Users';
import News from './pages/News';
import Profile from './pages/Profile';
import Audit from './pages/Audit';

function RequireAuth() {
  const { authed, ready } = useAuth();
  const loc = useLocation();
  if (!ready) {
    return <div className="splash"><span className="spinner big" /></div>;
  }
  if (!authed) {
    return <Navigate to="/login" replace state={{ from: loc.pathname }} />;
  }
  return <Outlet />;
}

// Egasi va admin — ikkalasi ham hamma bo'limni ko'radi (rol/perms yo'q).
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Landing />} />
      <Route path="/login" element={<Login />} />
      <Route element={<RequireAuth />}>
        <Route path="/app" element={<Layout />}>
          <Route index element={<Overview />} />
          <Route path="map" element={<MapPage />} />
          <Route path="feed" element={<Feed />} />
          <Route path="threats" element={<Threats />} />
          <Route path="devices" element={<Devices />} />
          <Route path="groups" element={<Groups />} />
          <Route path="users" element={<Users />} />
          <Route path="news" element={<News />} />
          <Route path="audit" element={<Audit />} />
          <Route path="profile" element={<Profile />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
