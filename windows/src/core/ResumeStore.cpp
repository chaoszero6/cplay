#include "ResumeStore.h"
#include "PlaybackRules.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>
#include <algorithm>

ResumeStore::ResumeStore(QString filePath) : m_path(std::move(filePath)) {}

void ResumeStore::load()
{
    m_entries.clear();
    QFile f(m_path);
    if (!f.open(QIODevice::ReadOnly)) return;
    const auto arr = QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("entries")).toArray();
    for (const auto &v : arr) {
        const auto o = v.toObject();
        Entry e;
        e.key = o.value(QStringLiteral("key")).toString();
        if (e.key.isEmpty()) continue;
        e.title = o.value(QStringLiteral("title")).toString();
        e.positionMs = qint64(o.value(QStringLiteral("position")).toDouble());
        e.durationMs = qint64(o.value(QStringLiteral("duration")).toDouble());
        e.updatedMs = qint64(o.value(QStringLiteral("updated")).toDouble());
        e.playCount = o.value(QStringLiteral("plays")).toInt();
        m_entries.insert(e.key, e);
    }
}

bool ResumeStore::save() const
{
    QDir().mkpath(QFileInfo(m_path).absolutePath());
    QJsonArray arr;
    for (const auto &e : recent(MaxEntries)) {
        arr.append(QJsonObject{
            {QStringLiteral("key"), e.key},
            {QStringLiteral("title"), e.title},
            {QStringLiteral("position"), double(e.positionMs)},
            {QStringLiteral("duration"), double(e.durationMs)},
            {QStringLiteral("updated"), double(e.updatedMs)},
            {QStringLiteral("plays"), e.playCount},
        });
    }
    QSaveFile f(m_path);
    if (!f.open(QIODevice::WriteOnly)) return false;
    f.write(QJsonDocument(QJsonObject{{QStringLiteral("version"), 1}, {QStringLiteral("entries"), arr}}).toJson(QJsonDocument::Compact));
    return f.commit();
}

void ResumeStore::update(const QString &key, const QString &title, qint64 positionMs, qint64 durationMs,
                         bool finished, qint64 nowMs)
{
    if (key.isEmpty()) return;
    Entry e = m_entries.value(key);
    e.key = key;
    if (!title.isEmpty()) e.title = title;
    if (durationMs > 0) e.durationMs = durationMs;
    e.positionMs = finished ? e.durationMs : std::max<qint64>(0, positionMs);
    e.updatedMs = nowMs;
    if (finished) e.playCount += 1;
    m_entries.insert(key, e);
    if (m_entries.size() > MaxEntries) {
        const auto keep = recent(MaxEntries);
        m_entries.clear();
        for (const auto &k : keep) m_entries.insert(k.key, k);
    }
}

qint64 ResumeStore::startPositionFor(const QString &key) const
{
    const auto it = m_entries.constFind(key);
    if (it == m_entries.cend()) return 0;
    return PlaybackRules::resumeStartMs(it->positionMs, it->durationMs);
}

std::optional<ResumeStore::Entry> ResumeStore::entry(const QString &key) const
{
    const auto it = m_entries.constFind(key);
    if (it == m_entries.cend()) return std::nullopt;
    return *it;
}

QList<ResumeStore::Entry> ResumeStore::recent(int limit) const
{
    QList<Entry> all = m_entries.values();
    std::sort(all.begin(), all.end(), [](const Entry &a, const Entry &b) {
        return a.updatedMs != b.updatedMs ? a.updatedMs > b.updatedMs : a.key < b.key;
    });
    if (all.size() > limit) all.resize(limit);
    return all;
}

void ResumeStore::remove(const QString &key) { m_entries.remove(key); }

void ResumeStore::rename(const QString &oldKey, const QString &newKey)
{
    auto it = m_entries.find(oldKey);
    if (it == m_entries.end() || oldKey == newKey) return;
    Entry e = it.value();
    m_entries.erase(it);
    e.key = newKey;
    m_entries.insert(newKey, e);
}
void ResumeStore::clear() { m_entries.clear(); }
