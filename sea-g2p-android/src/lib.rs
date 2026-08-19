pub mod core;
pub mod g2p;
pub mod lang;
pub mod punc;

use std::sync::{OnceLock, RwLock};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jboolean, JNI_TRUE, JNI_FALSE};

pub struct G2P {
    normalizer: lang::vi::Normalizer,
    engine: g2p::G2PEngine,
}

impl G2P {
    pub fn new_with_dict(dict_path: &str) -> Self {
        let normalizer = lang::vi::Normalizer::new("vi", Some(dict_path));
        let engine = g2p::G2PEngine::new(dict_path).unwrap_or_else(|_| g2p::G2PEngine::empty());
        Self { normalizer, engine }
    }

    pub fn new_default() -> Self {
        let default_paths = [
            std::env::var("SEA_G2P_DICT_PATH").unwrap_or_default(),
            "/data/user/0/com.skul9x.doctruyen/files/vieneu/sea_g2p.bin".to_string(),
            "/data/data/com.skul9x.doctruyen/files/vieneu/sea_g2p.bin".to_string(),
            "/data/local/tmp/sea_g2p.bin".to_string(),
            "sea_g2p.bin".to_string(),
            "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/app/src/main/assets/vieneu/sea_g2p.bin".to_string(),
        ];

        for path in &default_paths {
            if !path.is_empty() && std::path::Path::new(path).exists() {
                return Self::new_with_dict(path);
            }
        }

        let normalizer = lang::vi::Normalizer::new("vi", None);
        let engine = g2p::G2PEngine::empty();
        Self { normalizer, engine }
    }

    pub fn phonemize(&self, text: &str) -> String {
        if text.is_empty() {
            return String::new();
        }
        let normalized = self.normalizer.normalize(text, true);
        if normalized.is_empty() {
            return String::new();
        }
        self.engine.phonemize(&normalized)
    }
}

static G2P_INSTANCE: OnceLock<RwLock<G2P>> = OnceLock::new();

fn get_g2p_lock() -> &'static RwLock<G2P> {
    G2P_INSTANCE.get_or_init(|| RwLock::new(G2P::new_default()))
}

#[no_mangle]
pub extern "C" fn Java_com_skul9x_doctruyen_tts_vieneu_SeaG2P_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    dict_path: JString,
) -> jboolean {
    let path_str: String = match env.get_string(&dict_path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    if std::path::Path::new(&path_str).exists() {
        let new_g2p = G2P::new_with_dict(&path_str);
        if let Ok(mut lock) = get_g2p_lock().write() {
            *lock = new_g2p;
            return JNI_TRUE;
        }
    }
    JNI_FALSE
}

#[no_mangle]
pub extern "C" fn Java_com_skul9x_doctruyen_tts_vieneu_SeaG2P_nativePhonemize(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let text: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let phonemes = {
        let lock = get_g2p_lock().read().unwrap();
        lock.phonemize(&text)
    };
    let output = env.new_string(phonemes)
        .expect("Failed to create Java string");
    output.into_raw()
}
