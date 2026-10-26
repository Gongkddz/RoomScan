import * as THREE from 'three';

/** First-person controller. Camera motion never depends on an orbit target. */
export class GameController {
  constructor(camera, { worldUp = new THREE.Vector3(0, 1, 0), speed = 1, sensitivity = .003 } = {}) {
    this.camera = camera; this.worldUp = worldUp.clone().normalize();
    this.speed = speed; this.sensitivity = sensitivity; this.mode = 'fly';
    this.input = { right: 0, forward: 0, rise: 0, roll: 0, boost: false };
    this.velocity = new THREE.Vector3(); this.lookYaw = 0; this.lookPitch = 0;
    this.initialPosition = camera.position.clone(); this.initialQuaternion = camera.quaternion.clone();
  }
  setStick(x, y) {
    const magnitude = Math.min(1, Math.hypot(x, y)), deadZone = .08;
    if (magnitude <= deadZone) { this.releaseStick(); return; }
    const amount = ((magnitude - deadZone) / (1 - deadZone)) ** 1.35, length = Math.hypot(x, y);
    this.input.right = x / length * amount;
    this.input.forward = -y / length * amount; // Screen-up means camera-forward (-Z).
  }
  releaseStick() { this.input.right = 0; this.input.forward = 0; }
  look(dx, dy) { this.lookYaw -= dx * this.sensitivity; this.lookPitch -= dy * this.sensitivity; }
  rotateRoll(radians) {
    this.camera.quaternion.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, -1), radians)).normalize();
  }
  reset() {
    this.camera.position.copy(this.initialPosition); this.camera.quaternion.copy(this.initialQuaternion);
    this.velocity.set(0, 0, 0); this.lookYaw = this.lookPitch = 0;
    this.releaseStick(); this.input.rise = this.input.roll = 0;
  }
  update(seconds) {
    const dt = Math.max(0, Math.min(seconds, .05)), smoothing = 1 - Math.exp(-dt / .025);
    const yaw = this.lookYaw * smoothing; let pitch = this.lookPitch * smoothing;
    this.lookYaw -= yaw; this.lookPitch -= pitch;
    this.camera.quaternion.premultiply(new THREE.Quaternion().setFromAxisAngle(this.worldUp, yaw));
    if (this.mode === 'walk') {
      const elevation = Math.asin(THREE.MathUtils.clamp(this.camera.getWorldDirection(new THREE.Vector3()).dot(this.worldUp), -1, 1));
      pitch = THREE.MathUtils.clamp(pitch, -1.48 - elevation, 1.48 - elevation);
    }
    this.camera.quaternion.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), pitch));
    this.camera.quaternion.normalize();
    if (this.input.roll) this.rotateRoll(this.input.roll * dt);
    const forward = this.camera.getWorldDirection(new THREE.Vector3()).normalize();
    const right = new THREE.Vector3(1, 0, 0).applyQuaternion(this.camera.quaternion).normalize();
    if (this.mode === 'walk') {
      forward.addScaledVector(this.worldUp, -forward.dot(this.worldUp)).normalize();
      right.crossVectors(forward, this.worldUp).normalize();
    }
    const target = new THREE.Vector3().addScaledVector(right, this.input.right)
      .addScaledVector(forward, this.input.forward).addScaledVector(this.worldUp, this.input.rise);
    if (target.lengthSq() > 1) target.normalize();
    target.multiplyScalar(this.speed * (this.input.boost ? 2 : 1));
    const tau = .065, decay = Math.exp(-dt / tau);
    // Exact integration gives consistent acceleration and braking across refresh rates.
    this.camera.position.add(target.clone().multiplyScalar(dt)
      .add(this.velocity.clone().sub(target).multiplyScalar(tau * (1 - decay))));
    this.velocity.sub(target).multiplyScalar(decay).add(target);
    if (!target.lengthSq() && this.velocity.lengthSq() < 1e-7) this.velocity.set(0, 0, 0);
  }
}

export function orientCapture(camera, view) {
  const matrix = new THREE.Matrix4().set(...view.camera_to_world.flat());
  matrix.multiply(new THREE.Matrix4().makeRotationZ((view.display_rotation_degrees || 0) * Math.PI / 180));
  camera.position.setFromMatrixPosition(matrix); camera.quaternion.setFromRotationMatrix(matrix).normalize();
  let up = view.gravity_device ? new THREE.Vector3(...view.gravity_device).transformDirection(matrix)
    : new THREE.Vector3().setFromMatrixColumn(matrix, 1);
  if (view.world_up) up = new THREE.Vector3(...view.world_up);
  camera.up.copy(up.normalize()); return up;
}

/** Scale and rotate actual scene geometry; never emulate model scale with FOV. */
export class SceneTransform {
  constructor(group, mesh, center) {
    this.group = group; this.scale = 1; this.initialCenter = center.clone();
    group.position.copy(center); mesh.position.copy(center).negate(); group.add(mesh);
  }
  setScale(value) {
    this.scale = THREE.MathUtils.clamp(value, .05, 20);
    this.group.scale.setScalar(this.scale); this.group.updateMatrixWorld(true);
  }
  rotateGesture(dx, dy, twist, camera, worldUp) {
    const right = new THREE.Vector3(1,0,0).applyQuaternion(camera.quaternion);
    const forward = camera.getWorldDirection(new THREE.Vector3());
    this.group.quaternion.premultiply(new THREE.Quaternion().setFromAxisAngle(worldUp, dx*.003));
    this.group.quaternion.premultiply(new THREE.Quaternion().setFromAxisAngle(right, dy*.003));
    this.group.quaternion.premultiply(new THREE.Quaternion().setFromAxisAngle(forward, twist)).normalize();
    this.group.updateMatrixWorld(true);
  }
  modelDisplacement(worldStart, worldEnd) {
    this.group.updateMatrixWorld(true);
    return this.group.worldToLocal(worldEnd.clone()).sub(this.group.worldToLocal(worldStart.clone()));
  }
  panGesture(dx, dy, camera, viewportHeight) {
    const forward = camera.getWorldDirection(new THREE.Vector3());
    const depth = Math.max(camera.near * 10, Math.abs(this.group.position.clone().sub(camera.position).dot(forward)));
    const perPixel = 2 * depth * Math.tan(camera.fov * Math.PI / 360) / Math.max(1, viewportHeight);
    this.group.position.addScaledVector(new THREE.Vector3(1,0,0).applyQuaternion(camera.quaternion), dx * perPixel)
      .addScaledVector(new THREE.Vector3(0,1,0).applyQuaternion(camera.quaternion), -dy * perPixel);
    this.group.updateMatrixWorld(true);
  }
  reset() { this.group.position.copy(this.initialCenter); this.group.quaternion.identity(); this.setScale(1); }
}
