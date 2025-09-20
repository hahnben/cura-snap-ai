# Git Workflow Standards für CuraSnap AI

## Commit Message Konventionen

### Format
```
<type>: <subject>

<body>

<footer>
```

### Types
- `feat:` - Neue Features
- `fix:` - Bugfixes
- `refactor:` - Code-Refactoring ohne funktionale Änderungen
- `docs:` - Dokumentation
- `style:` - Code-Formatierung (keine funktionalen Änderungen)
- `test:` - Tests hinzufügen oder ändern
- `chore:` - Build-Prozess oder Hilfswerkzeuge

### Beispiele
```
feat: implement voice recording for patient notes
fix: resolve CORS issues for HTTPS frontend connections
refactor: improve audio-first layout component structure
```

## Verbotene Praktiken

### ❌ NIEMALS in Commits
- **Claude Code Acknowledgments**: Keine "🤖 Generated with [Claude Code]" Zeilen
- **Persönliche API Keys**: Keine echten Produktions-Secrets
- **Excessive Details**: Commit Messages sollten prägnant sein (<50 Zeichen Subject)

### ❌ Vermeiden
- Fix-Commit-Ketten: Besser initial korrekt implementieren
- Work-in-Progress Commits ohne Squashing vor Merge
- Commits mit "WIP", "temp", "debug" ohne später zu bereinigen

## Branch Management

### Branch Naming
```
feature/beschreibung-der-funktion
fix/beschreibung-des-problems
refactor/bereich-der-refactoring
security/sicherheits-verbesserung
```

### Branch Lifecycle
1. **Erstellen**: `git checkout -b feature/neue-funktion`
2. **Entwickeln**: Kleine, logische Commits
3. **Vor Merge**: Tests laufen, Code Review
4. **Nach Merge**: Branch lokal löschen `git branch -d feature/neue-funktion`

### Branch Cleanup
```bash
# Merged branches identifizieren und löschen
git branch --merged main | grep -v "main\|*" | xargs git branch -d
```

## Sicherheit

### Erlaubte Dateien
- ✅ Development Certificates (mkcert, localhost)
- ✅ Beispiel-Konfigurationsdateien (.env.example)
- ✅ Public Keys

### Verbotene Dateien
- ❌ Production API Keys
- ❌ Passwörter oder Secrets
- ❌ Private Keys (.key, .pem mit privaten Schlüsseln)
- ❌ Database Credentials

## Commit Qualität

### Gute Commits
- Atomare Änderungen (eine logische Einheit)
- Klare, beschreibende Messages
- Tests laufen erfolgreich
- Code ist formatiert (ESLint, Prettier)

### Code Review Checklist
- [ ] Commit Message folgt Konventionen
- [ ] Keine Claude Acknowledgments
- [ ] Tests laufen erfolgreich
- [ ] Keine Secrets im Code
- [ ] Code ist dokumentiert
- [ ] Folgt Projektkonventionen

## Rollback-Strategien

### Sichere Rollback-Tags
```bash
# Vor riskanten Operationen Backup erstellen
git tag backup-before-operation-$(date +%Y%m%d-%H%M%S)

# Rollback wenn nötig
git reset --hard backup-before-operation-20250920-121037
```

### Interactive Rebase Vorsicht
⚠️ **NIEMALS** interactive rebase auf Commits verwenden, die bereits in `main` sind!

```bash
# Sicher: Nur lokale Commits ändern
git rebase -i HEAD~3  # Nur wenn Commits nicht in main

# Unsicher: Commits in main ändern
git rebase -i main~5  # NIEMALS - beschädigt main
```

## Automatisierung

### Pre-commit Hooks
```bash
# ESLint für Frontend
npm run lint

# Tests laufen
npm test

# Backend Tests
./mvnw test
```

### Git Aliases (Empfohlen)
```bash
git config --global alias.st status
git config --global alias.co checkout
git config --global alias.br branch
git config --global alias.cleanup "!git branch --merged main | grep -v 'main\\|*' | xargs git branch -d"
```

## Letzte Aktualisierung
Erstellt: 2025-09-20 nach Git History Cleanup
Grund: Verhinderung von Claude Acknowledgments und bessere Repository-Hygiene