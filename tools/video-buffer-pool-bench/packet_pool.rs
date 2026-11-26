//! A fixed-size packet arena for a receive session. A packet stays checked out
//! while the FEC queue or the decode unit owns it. Either thread may return it.
use std::alloc::{alloc, dealloc, Layout};
use std::ptr::{self, NonNull};
use std::sync::Mutex;

struct Slots {
    available: Vec<usize>,
    checked_out_peak: usize,
    takes: u64,
    exhausted: u64,
}

pub struct PacketPool {
    base: NonNull<u8>,
    layout: Layout,
    stride: usize,
    count: usize,
    slots: Mutex<Slots>,
}

// The arena address is fixed. The mutex transfers exclusive ownership of each
// individual slot. A caller returns a slot only after its last access.
unsafe impl Sync for PacketPool {}

impl Drop for PacketPool {
    fn drop(&mut self) {
        unsafe { dealloc(self.base.as_ptr(), self.layout) };
    }
}

#[no_mangle]
pub extern "C" fn pool_create(size: usize, count: usize) -> *mut PacketPool {
    let Some(rounded) = size.checked_add(15) else { return ptr::null_mut() };
    let stride = rounded & !15;
    let Some(bytes) = stride.checked_mul(count) else { return ptr::null_mut() };
    if bytes == 0 { return ptr::null_mut() }
    let Ok(layout) = Layout::from_size_align(bytes, 16) else { return ptr::null_mut() };
    let mut available = Vec::new();
    if available.try_reserve_exact(count).is_err() { return ptr::null_mut() }
    available.extend((0..count).rev());
    let Some(base) = NonNull::new(unsafe { alloc(layout) }) else { return ptr::null_mut() };
    Box::into_raw(Box::new(PacketPool {
        base, layout, count, stride,
        slots: Mutex::new(Slots { available, checked_out_peak: 0, takes: 0, exhausted: 0 }),
    }))
}

/// The returned pointer is valid until the matching pool_return. NULL asks the
/// caller to use its ordinary allocator. The pool itself must outlive every loan.
#[no_mangle]
pub unsafe extern "C" fn pool_take(pool: &PacketPool, size: usize) -> *mut u8 {
    if size > pool.stride { return ptr::null_mut() }
    let mut slots = pool.slots.lock().unwrap();
    let Some(index) = slots.available.pop() else {
        slots.exhausted += 1;
        return ptr::null_mut();
    };
    slots.takes += 1;
    slots.checked_out_peak = slots.checked_out_peak.max(pool.count - slots.available.len());
    pool.base.as_ptr().add(index * pool.stride)
}

/// Return each loan exactly once. Non-arena pointers belong to the C allocator.
#[no_mangle]
pub unsafe extern "C" fn pool_return(pool: &PacketPool, packet: *mut u8) -> bool {
    let offset = (packet as usize).wrapping_sub(pool.base.as_ptr() as usize);
    if offset >= pool.layout.size() { return false }
    assert_eq!(offset % pool.stride, 0);
    pool.slots.lock().unwrap().available.push(offset / pool.stride);
    true
}

/// Called after both the receiver and consumers have stopped.
#[no_mangle]
pub unsafe extern "C" fn pool_finish(pool: *mut PacketPool, stats: *mut u64) -> bool {
    let pool = Box::from_raw(pool);
    let slots = pool.slots.lock().unwrap();
    let out = std::slice::from_raw_parts_mut(stats, 4);
    out.copy_from_slice(&[slots.takes, slots.exhausted, slots.checked_out_peak as u64, pool.layout.size() as u64]);
    slots.available.len() == pool.count
}
