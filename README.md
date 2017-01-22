# Overview

Coalesce is a (optionally distributed) task execution system to support monitoring use-cases.
Tasks are pinned to a given *MEMBER* node as long as that *MEMBER* is considered alive by the
*LEADER*. The *LEADER* is responsible for determining how tasks are distributed by assigning them
to the *MEMBER* that should be executing it.

The goal is to allow for reliable non-centralized data collection resulting in time series and log
data that can be ingested by another system (e.g. Heroic, Elasticsearch).

* [Task Definition](#task-definition)
* [Sync Protocols](#sync-protocols)
* [Task Source](#task-source)
* [Storage](#storage)
* [Data Structure](#data-structure)
* [Publisher](#publisher)

# Task Definition

Task definitions are JSON documents.

They have a **type** field defining what type of task it is, which then allows the system to
determine which model to load for a given task.

# Sync Protocols

Sync protocols are responsible for enforcing guarantees specified for participants of a cluster.

A cluster contains two different kinds of members; a *LEADER* and zero or more *MEMBER*s.

## Local Sync (`type = 'local'`)

To run the service in a single instance, a local protocol can be used.

This is a simplified protocol that assumed that the local node is always the *LEADER*, and
the sole *MEMBER* of a cluster.

## Zookeeper Sync (`type = 'zookeeper'`)

#### `/leader`

Uses an exclusive lock to determine who is the *LEADER* of the cluster.
Only one process may believe that they are *LEADER* at any given point in time.

Leaders are responsible for discovering tasks and distributing them to members by assigning them.

#### `/members/<member-id>`

Members add an EPHEMERAL node here which is watched by the elected *LEADER*.
When members are added or removed, the *LEADER* is responsible for assigning tasks.

#### `/assign/<member-id>/<task-id>`

Each *MEMBER* watches their directory (`/assign/<member-id>`) and when the *LEADER* adds task is
responsible for downloading the task and running it as appropriate.

A member MAY cache this data in memory and its local file system, but may only use this cache when
Zookeeper is unavailable.

All nodes in here are `PERSISTENT` nodes, to allow for masters coming and going.

A *LEADER* must never re-assign tasks if their state is SUSPENDED or LOST. When a *LEADER* has just
been elected they must cache a local view of all assigned tasks so that they can make decisions on
what and how to re-assign when comparing this view to the members view.
# Task Source

Tasks are stored either in the filesystem of all nodes, or in Datastore.

All tasks have a `<task-id>` associated with.

When stored in a `directory`, the `<task-id>` is the name of the file, excluding its extension.

When stored in `datastore`, the `<task-id>` is contained in the id of the entity.

## Directory Task Source (`type = 'directory'`)

This is the simplest form of task source where all tasks are put in one or more directory.

These are periodically scanned by the leader, which assigns the `<task-id>` from the basename of
the file containing the task definition.

The format of the task is determined by its file extension.

#### YAML (file extension: `.yaml`)

The task in YAML format. This is preferred for directory task sources since it is more human
readable and more easily supports editing on a per-line basis.

#### JSON (file extension: `.json`)

The task in JSON format.

This is supported so that tasks can be downloaded from other sources and easily integrated with the
directory source.

## Datastore Task Source (`type = 'datastore'`)

#### `/coalesce-tasks:<task-id>`

All tasks are stored in this path.

They contain the following properties.

**version**, a string designating which task version is being used.

**data**, a json-serialized representation of the task.

**last_updated (indexed)**, a node monotonic timestamp for when the task was last updated. this is
indexed to allow a *LEADER* to find all tasks that were recently updated using a `greater than`
filter. the actual filtering being performed will be fuzzed by N seconds to permit clock drift,
and the consistency guarantees provided by Datastore.

**version**, a random string that indicates the version of the task. if this has changed, it
indicated that the task should be reloaded by a *MEMBER*.

**metadata (indexed)**, a list of metadata tokens encoding key-value pairs of information. These
can be used to filter the tasks arbitrarily
(e.g. `metadata = "squad=backend" AND metadata = "owner = udoprog"`).

A new *LEADER* joining will download all known task ids using a `__key__` query, after which it
will use the current timestamp as the `last_update` value.

A *LEADER* must also periodically re-scan all known task ids to avoid missing updated tasks, this
frequency can however be much lower than the periodic scanning for new tasks.

A *MEMBER* will periodically query the `version` field to determine if the task definition has
changed. On changes a `worker` will update the task.

# Storage

Storage is used to store non-critical information about tasks, which is non-the-less very useful to
end users.

## Datastore Storage (`type = 'datastore'`)

#### `/coalesce-logs:<id>`

This contains log entries for each task. Note that `<id>` above is a generated id, and not
`<task-id>`.

Each log entry contains the following properties.

**timestamp (+indexed)**, a numeric timestamp indicating when the log entry was created according to who
reported it.

**task_id (indexed)**, the id of the task being run.

**type (indexed)**, the type of the log entry. this is also included in `data`, but we have it here to allow
for filtering.

**data**, a json-serialized representation of the log entry.

##### `type = 'run-error'`

This type indicates that an error occurred when running the given task.

This has the following fields.

**message**, an end-user message of what went wrong.

##### `type = 'malformed-task-error'`

This type indicates that a task has formatting issues.
This message is emitted only once by a *MEMBER* if it discovers that a task is malformed for some
reason. The *MEMBER* will retain ownership of the task.

This has the following fields.

**message**, an end-user message of what went wrong.

**storage**, storage used for fetching the task.

**storage.type**, type of storage used for fetching the task.

###### `storage.type = 'directory'`

**path**, path to the file containing the task definition.

**location**, a list pair containing `{line: <number>, column: <number}` describing where the error
is located in the file.

**line**, extracted lines of the configuration where the error occured.

# Data Structure

The internal data structure is a single `Sample`.

Each sample has a set of tags associated with them describing what is being measured.

## Point Sample

A `Point` is a `Sample` containing a timestamp and a single numeric measurement.

Example JSON: `[<timestamp>, <value>]`, or `[1234567890, 42.0]`.

# Publisher

The publisher is responsible for publishing collected data to be ingested by other systems.

TODO: This section needs a lot more fleshing out.

## Pub/Sub publisher (`type = 'pubsub'`)

Publishes data over Pub/Sub.

## Pub/Sub publisher (`type = 'kafka'`)

Publishes data over Kafka.

## Elasticsearch publisher (`type = 'elasticsearch'`)

Publishes data over Elasticsearch.
