use anyhow::{Result, anyhow, ensure};
use std::collections::HashMap;
use std::sync::Arc;

/// Handles never repeat during the process lifetime, including when a slot is reused.
pub(super) struct Sessions<T> {
    next: i64,
    limit: usize,
    active: HashMap<i64, Arc<T>>,
}

impl<T> Sessions<T> {
    pub(super) fn new(limit: usize) -> Self {
        Self {
            next: 0,
            limit,
            active: HashMap::new(),
        }
    }

    pub(super) fn add(&mut self, value: Arc<T>) -> Result<i64> {
        ensure!(
            self.active.len() < self.limit,
            "Too many open native sessions"
        );
        self.next = self
            .next
            .checked_add(1)
            .ok_or_else(|| anyhow!("Handle range exhausted"))?;
        self.active.insert(self.next, value);
        Ok(self.next)
    }

    pub(super) fn get(&self, id: i64) -> Result<Arc<T>> {
        self.active
            .get(&id)
            .cloned()
            .ok_or_else(|| anyhow!("Native session is closed"))
    }

    pub(super) fn remove(&mut self, id: i64) -> Option<Arc<T>> {
        self.active.remove(&id)
    }

    pub(super) fn snapshot(&self) -> Vec<Arc<T>> {
        self.active.values().cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stale_handles_cannot_resolve_new_sessions_and_in_flight_owners_survive_close() {
        let mut sessions = Sessions::new(2);
        let first = sessions.add(Arc::new("first")).unwrap();
        let second = sessions.add(Arc::new("second")).unwrap();
        assert!(sessions.add(Arc::new("over limit")).is_err());
        let in_flight = sessions.get(first).unwrap();
        sessions.remove(first);
        let third = sessions.add(Arc::new("third")).unwrap();
        assert!(sessions.get(first).is_err());
        assert!(sessions.remove(first).is_none());
        assert_eq!(*in_flight, "first");
        assert_eq!(*sessions.get(second).unwrap(), "second");
        assert_eq!(*sessions.get(third).unwrap(), "third");
    }
}
