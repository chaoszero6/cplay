#pragma once
#include <QHash>
#include <QString>

/**
 * Passwords and access tokens of saved servers, encrypted for the current
 * Windows user with DPAPI (CryptProtectData), in secrets.json next to the
 * settings. They are only sent to the server they belong to. (The Linux test
 * build only obscures them; it is not a shipped product.)
 */
class SecretStore
{
public:
    explicit SecretStore(QString filePath);
    QString get(const QString &id) const { return m_values.value(id); }
    void set(const QString &id, const QString &secret);
    void remove(const QString &id);

    static QByteArray protect(const QByteArray &plain);
    static QByteArray unprotect(const QByteArray &sealed);

private:
    void load();
    void save() const;
    QString m_path;
    QHash<QString, QString> m_values;
};
