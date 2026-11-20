#include "SecretStore.h"

#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

#ifdef Q_OS_WIN
#include <windows.h>
#include <dpapi.h>
#endif

SecretStore::SecretStore(QString filePath) : m_path(std::move(filePath)) { load(); }

QByteArray SecretStore::protect(const QByteArray &plain)
{
#ifdef Q_OS_WIN
    DATA_BLOB in{DWORD(plain.size()), reinterpret_cast<BYTE *>(const_cast<char *>(plain.constData()))};
    DATA_BLOB out{};
    if (!CryptProtectData(&in, L"CPlay", nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out)) return {};
    QByteArray sealed(reinterpret_cast<const char *>(out.pbData), int(out.cbData));
    LocalFree(out.pbData);
    return sealed;
#else
    QByteArray o = plain;
    for (int i = 0; i < o.size(); ++i) o[i] = char(o[i] ^ 0x5A);
    return o;
#endif
}

QByteArray SecretStore::unprotect(const QByteArray &sealed)
{
#ifdef Q_OS_WIN
    DATA_BLOB in{DWORD(sealed.size()), reinterpret_cast<BYTE *>(const_cast<char *>(sealed.constData()))};
    DATA_BLOB out{};
    if (!CryptUnprotectData(&in, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out)) return {};
    QByteArray plain(reinterpret_cast<const char *>(out.pbData), int(out.cbData));
    SecureZeroMemory(out.pbData, out.cbData);
    LocalFree(out.pbData);
    return plain;
#else
    return protect(sealed);
#endif
}

void SecretStore::load()
{
    QFile f(m_path);
    if (!f.open(QIODevice::ReadOnly)) return;
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    for (auto it = o.begin(); it != o.end(); ++it)
        m_values.insert(it.key(), QString::fromUtf8(unprotect(QByteArray::fromBase64(it.value().toString().toLatin1()))));
}

void SecretStore::save() const
{
    QJsonObject o;
    for (auto it = m_values.begin(); it != m_values.end(); ++it)
        o.insert(it.key(), QString::fromLatin1(protect(it.value().toUtf8()).toBase64()));
    QSaveFile f(m_path);
    if (!f.open(QIODevice::WriteOnly)) return;
    f.write(QJsonDocument(o).toJson(QJsonDocument::Compact));
    f.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    f.commit();
}

void SecretStore::set(const QString &id, const QString &secret)
{
    if (secret.isEmpty()) { remove(id); return; }
    m_values.insert(id, secret);
    save();
}

void SecretStore::remove(const QString &id)
{
    if (m_values.remove(id)) save();
}
