interface PageHeroProps {
  label: string;
  title: React.ReactNode;
  subtitle: string;
  actions?: React.ReactNode;
}

export function PageHero({ label, title, subtitle, actions }: PageHeroProps) {
  return (
    <section className="hero">
      <div className="hero-row">
        <div>
          <div className="section-label">{label}</div>
          <h1 className="page-title">{title}</h1>
          <p className="page-subtitle">{subtitle}</p>
        </div>
        {actions && <div className="hero-actions">{actions}</div>}
      </div>
    </section>
  );
}
