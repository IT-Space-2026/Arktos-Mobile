//! Desktop `crate::autofill` surface — the password/form records
//! the SYNC08 vault adapter merges over.

/// A saved login form — same record the Qt side's autofill.dat
/// carries; the sync adapter merges by (url, name).
#[derive(Clone, Debug, PartialEq, Default)]
pub struct FormRecord {
    pub url: String,
    pub name: String,
    pub has_password: bool,
    pub elements: Vec<(String, String)>,
}
