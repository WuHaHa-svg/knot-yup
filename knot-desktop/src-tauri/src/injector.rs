//! macOS 系统输入注入（CGEvent FFI）。
//! 把触摸板事件注入为系统级光标移动/点击/滚动，作用于当前焦点窗口。
//! 只在 macOS 编译；其它平台提供 no-op 实现保证编译通过。

#[cfg(target_os = "macos")]
mod macos {
    use std::ffi::c_void;

    #[repr(C)]
    #[derive(Copy, Clone)]
    pub struct CGPoint { pub x: f64, pub y: f64 }

    #[link(name = "ApplicationServices", kind = "framework")]
    extern "C" {
        fn CGEventCreate(source: *const c_void) -> *mut c_void;
        fn CGEventCreateMouseEvent(source: *const c_void, mouse_type: u32, location: CGPoint, mouse_button: u32) -> *mut c_void;
        fn CGEventCreateScrollWheelEvent(source: *const c_void, units: u32, wheel_count: u32, w1: i32, w2: i32, w3: i32) -> *mut c_void;
        fn CGEventPost(tap: u32, event: *mut c_void);
        fn CGEventGetLocation(event: *const c_void) -> CGPoint;
        fn CFRelease(cf: *const c_void);
    }

    // CGEventType 真实 rawValue（经 Swift CGEventType.rawValue 核实）：
    // mouseMoved=5 而非 0（0 是 kCGEventNull，会被丢弃——曾导致光标不动、只有点击生效）
    const EVENT_MOUSE_MOVED: u32 = 5;
    const EVENT_LEFT_DOWN: u32 = 1;
    const EVENT_LEFT_UP: u32 = 2;
    const EVENT_RIGHT_DOWN: u32 = 3;
    const EVENT_RIGHT_UP: u32 = 4;
    const EVENT_OTHER_DOWN: u32 = 25;
    const EVENT_OTHER_UP: u32 = 26;
    const HID_TAP: u32 = 0;          // kCGHIDEventTap
    const SCROLL_UNIT_PIXEL: u32 = 0;
    const BTN_LEFT: u32 = 0;
    const BTN_RIGHT: u32 = 1;
    const BTN_CENTER: u32 = 2;

    /// 当前光标位置：CGEventCreate(NULL) 取当前事件快照，再读 location。
    fn cursor_pos() -> CGPoint {
        unsafe {
            let ev = CGEventCreate(std::ptr::null());
            if ev.is_null() { return CGPoint { x: 0.0, y: 0.0 }; }
            let p = CGEventGetLocation(ev);
            CFRelease(ev);
            p
        }
    }

    fn post(ev: *mut c_void) {
        if !ev.is_null() {
            unsafe { CGEventPost(HID_TAP, ev); CFRelease(ev); }
        }
    }

    pub fn move_relative(dx: i32, dy: i32) {
        let p = cursor_pos();
        let np = CGPoint { x: p.x + dx as f64, y: p.y + dy as f64 };
        unsafe { post(CGEventCreateMouseEvent(std::ptr::null(), EVENT_MOUSE_MOVED, np, BTN_LEFT)); }
    }

    fn btn_codes(button: &str) -> (u32, u32, u32) {
        match button {
            "right" => (EVENT_RIGHT_DOWN, EVENT_RIGHT_UP, BTN_RIGHT),
            "middle" => (EVENT_OTHER_DOWN, EVENT_OTHER_UP, BTN_CENTER),
            _ => (EVENT_LEFT_DOWN, EVENT_LEFT_UP, BTN_LEFT),
        }
    }

    pub fn click(button: &str) {
        let (dt, ut, btn) = btn_codes(button);
        let p = cursor_pos();
        unsafe {
            post(CGEventCreateMouseEvent(std::ptr::null(), dt, p, btn));
            post(CGEventCreateMouseEvent(std::ptr::null(), ut, p, btn));
        }
    }

    pub fn button_down(button: &str) {
        let (dt, _, btn) = btn_codes(button);
        let p = cursor_pos();
        unsafe { post(CGEventCreateMouseEvent(std::ptr::null(), dt, p, btn)); }
    }

    pub fn button_up(button: &str) {
        let (_, ut, btn) = btn_codes(button);
        let p = cursor_pos();
        unsafe { post(CGEventCreateMouseEvent(std::ptr::null(), ut, p, btn)); }
    }

    pub fn scroll(dx: i32, dy: i32) {
        unsafe { post(CGEventCreateScrollWheelEvent(std::ptr::null(), SCROLL_UNIT_PIXEL, 2, -dy, dx, 0)); }
    }

    /// 触发调度中心（Control+Up）——三指双击的固定动作。
    ///
    /// 系统级全局快捷键由 WindowServer 拦截，CGEventPost 注入的事件到不了这层
    /// （改 source / 改按键序列都无效，实测确认）。改走 System Events 的 AppleScript
    /// 按键——它经由系统的辅助功能通道，能触发 Spotlight / Mission Control 这类系统快捷键。
    pub fn mission_control() {
        use std::process::Command;
        let script = "tell application \"System Events\" to key code 126 using control down";
        if let Err(e) = Command::new("/usr/bin/osascript").args(["-e", script]).output() {
            tracing::warn!(target: "injector", "触发调度中心失败: {e}");
        }
    }

    /// 全局抬起（松开兜底：连接断开时避免按键卡住）。
    pub fn release_all() {
        button_up("left");
        button_up("right");
        button_up("middle");
    }

}

#[cfg(target_os = "macos")]
pub use macos::*;

// 非 macOS：no-op（Windows SendInput 留 T2）
#[cfg(not(target_os = "macos"))]
pub fn move_relative(_dx: i32, _dy: i32) {}
#[cfg(not(target_os = "macos"))]
pub fn click(_button: &str) {}
#[cfg(not(target_os = "macos"))]
pub fn button_down(_button: &str) {}
#[cfg(not(target_os = "macos"))]
pub fn button_up(_button: &str) {}
#[cfg(not(target_os = "macos"))]
pub fn scroll(_dx: i32, _dy: i32) {}
#[cfg(not(target_os = "macos"))]
pub fn release_all() {}
#[cfg(not(target_os = "macos"))]
pub fn mission_control() {}
