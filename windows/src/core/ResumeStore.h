#pragma once
#include <QHash>
#include <QList>
#include <QString>
#include <optional>

/**
 * Remembers where each video was left (like the Android watch history):
 * one JSON file, written atomically, newest entries kept first.
 */
class ResumeStore
{
public:
    struct Entry {
        QString key;      // local path or URL
        QString title;
        qint64 positionMs = 0;
        qint64 durationMs = 0;
        qint64 updatedMs = 0; // epoch ms
        int playCount = 0;
    };

    explicit ResumeStore(QString filePath);

    void load();
    bool save() const;

    /** Saves progress; [finished] counts a play and clears the resume point. */
    void update(const QString &key, const QString &title, qint64 positionMs, qint64 durationMs,
                bool finished, qint64 nowMs);
    /** Where to start [key], already filtered through PlaybackRules::resumeStartMs. */
    qint64 startPositionFor(const QString &key) const;
    std::optional<Entry> entry(const QString &key) const;
    /** Most recently played first. */
    QList<Entry> recent(int limit) const;
    void remove(const QString &key);
    /** A file was renamed or moved: its history follows it. */
    void rename(const QString &oldKey, const QString &newKey);
    void clear();

    static constexpr int MaxEntries = 500;

private:
    QString m_path;
    QHash<QString, Entry> m_entries;
};
