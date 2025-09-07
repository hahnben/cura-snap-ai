import { Link } from '@mui/material';

interface SkipLinkProps {
  href: string;
  children: React.ReactNode;
}

export function SkipLink({ href, children }: SkipLinkProps) {
  return (
    <Link
      href={href}
      sx={{
        position: 'absolute',
        top: -40,
        left: 6,
        zIndex: 999999,
        display: 'block',
        padding: '8px 16px',
        backgroundColor: 'primary.main',
        color: 'primary.contrastText',
        textDecoration: 'none',
        borderRadius: 1,
        fontSize: '0.875rem',
        fontWeight: 500,
        border: '2px solid transparent',
        '&:focus': {
          top: 6,
        },
        '&:hover': {
          textDecoration: 'underline',
        },
      }}
      onFocus={(e) => {
        e.currentTarget.style.top = '6px';
      }}
      onBlur={(e) => {
        e.currentTarget.style.top = '-40px';
      }}
    >
      {children}
    </Link>
  );
}